package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;
import net.minecraft.resources.ResourceLocation;

class TemplateEventPersistenceTest {
    @TempDir java.nio.file.Path directory;
    @BeforeEach void start() { CommandEventAction.start(); }
    @AfterEach void stop() { CommandEventAction.shutdown(); }

    private static class Group {
        final Map<Integer, NPC> npcs = new LinkedHashMap<>();
        final NPCRegistry registry = (NPCRegistry) Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(),
                new Class<?>[] {NPCRegistry.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> npcs.get(args[0]);
                    case "getByUniqueId" -> npcs.values().stream()
                            .filter(npc -> npc.getUniqueId().equals(args[0])).findFirst().orElse(null);
                    default -> throw new AssertionError(method.getName());
                });
        NPC create(UUID uuid) {
            int id = npcs.size();
            NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[] {NPC.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getOwningRegistry" -> registry;
                        case "getId" -> id;
                        case "getUniqueId" -> uuid;
                        case "isSpawned" -> false;
                        default -> throw new AssertionError(method.getName());
                    });
            npcs.put(id, npc);
            return npc;
        }
        NPC create() { return create(UUID.randomUUID()); }
    }

    private static Template definition(String key, Map<String, Object> commands) {
        var root = new MemoryDataKey();
        root.setRaw("commands", commands);
        return Template.load(new TemplateRegistry.TemplateErrorReporter(), null, ResourceLocation.parse(key), root);
    }

    private static MemoryDataKey snapshot(NPC npc) {
        var root = new MemoryDataKey();
        TemplateEventBindings.save(npc, root);
        return root;
    }

    @Test void savedAppliedDefinitionSurvivesFreshLifetimeAndYamlRoundTrip() {
        NPC npc = new Group().create();
        var commands = new LinkedHashMap<String, Object>();
        TemplateEventBindings.EVENT_TYPES.keySet().stream().sorted().forEach(event ->
                commands.put(event, List.of("npc name \"Version one\"", "say " + event)));
        commands.put("on_template_apply", List.of("npc remove --all"));
        definition("test:restart", commands).apply(npc);
        var saved = new YamlStorage(directory.resolve("npc.yml").toFile());
        TemplateEventBindings.save(npc, saved.getKey("template-events"));
        saved.save();
        var expected = snapshot(npc).getValuesDeep();
        assertFalse(expected.toString().contains("on_template_apply"));
        CommandEventAction.start();
        NPC restored = new Group().create(npc.getUniqueId());
        var input = new YamlStorage(directory.resolve("npc.yml").toFile());
        assertTrue(input.load());
        definition("test:restart", Map.of("on_spawn", List.of("say unused version two")));
        TemplateEventBindings.prepareLoad(input.getKey("template-events")).accept(restored);
        assertEquals(expected, snapshot(restored).getValuesDeep());
        assertNotEquals(expected, snapshot(npc).getValuesDeep());
        var copied = new Group().create();
        TemplateEventBindings.prepareLoad(input.getKey("template-events")).accept(copied);
        assertEquals(expected, snapshot(copied).getValuesDeep());
    }

    @Test void reapplicationAndExplicitEmptySnapshotDoNotResurrectOldCommands() {
        NPC npc = new Group().create();
        definition("test:first", Map.of("on_spawn", List.of("say first"))).apply(npc);
        definition("test:other", Map.of("on_death", List.of("say other"))).apply(npc);
        definition("test:first", Map.of("on_spawn", List.of("say changed"))).apply(npc);
        var expected = snapshot(npc).getValuesDeep();
        var saved = snapshot(npc);
        TemplateEventBindings.prepareLoad(saved).accept(npc);
        TemplateEventBindings.prepareLoad(saved).accept(npc);
        assertEquals(expected, snapshot(npc).getValuesDeep());
        definition("test:first", Map.of()).apply(npc);
        assertFalse(snapshot(npc).getValuesDeep().toString().contains("say changed"));
        assertTrue(snapshot(npc).getValuesDeep().toString().contains("say other"));
        var empty = snapshot(new Group().create());
        TemplateEventBindings.prepareLoad(empty).accept(npc);
        assertEquals(empty.getValuesDeep(), snapshot(npc).getValuesDeep());
    }

    @Test void restoredCandidateDoesNotDuplicateTransferredBindingsAndRollbackPreservesOriginal() {
        var old = new Group(); NPC npc = old.create();
        definition("test:transfer", Map.of("on_spawn", List.of("say once"))).apply(npc);
        var saved = snapshot(npc);
        for (boolean commit : List.of(false, true)) {
            var next = new Group(); NPC candidate = next.create(npc.getUniqueId());
            try (var transfer = CommandEventAction.prepareRegistryTransfer(old.registry, next.registry)) {
                TemplateEventBindings.prepareLoad(saved).accept(candidate);
                if (commit) transfer.commit();
            }
            assertEquals(saved.getValuesDeep(), snapshot(commit ? candidate : npc).getValuesDeep());
            assertEquals(snapshot(new Group().create()).getValuesDeep(), snapshot(commit ? npc : candidate).getValuesDeep());
        }
    }

    @Test void malformedSnapshotIsRejectedBeforeChangingExistingBindings() {
        NPC npc = new Group().create();
        definition("test:valid", Map.of("on_spawn", List.of("say retained"))).apply(npc);
        var expected = snapshot(npc).getValuesDeep();
        var valid = Map.of("key", "test:valid", "commands", Map.of("on_spawn", List.of("say new")));
        var invalid = new ArrayList<Object>();
        invalid.add(Map.of("version", 2, "templates", List.of()));
        invalid.add(Map.of("version", 1, "templates", List.of(valid, valid)));
        invalid.add(Map.of("version", 1, "templates", Map.of("1", valid)));
        invalid.add(Map.of("version", 1, "templates", List.of(Map.of("key", "bad key", "commands", Map.of()))));
        for (Object commands : List.of(Map.of("on_template_apply", List.of("say forbidden")),
                Map.of("on_spawn", List.of(7)), Map.of("on_spawn", "say scalar"),
                Map.of("unknown", List.of("say unknown")))) {
            invalid.add(Map.of("version", 1, "templates", List.of(valid, Map.of("key", "test:bad", "commands", commands))));
        }
        for (Object raw : invalid) {
            var root = new MemoryDataKey(); root.setRaw("", raw);
            assertThrows(IllegalArgumentException.class, () -> TemplateEventBindings.prepareLoad(root), raw.toString());
            assertEquals(expected, snapshot(npc).getValuesDeep());
        }
        TemplateEventBindings.prepareLoad(new MemoryDataKey()).accept(npc);
        assertEquals(expected, snapshot(npc).getValuesDeep());
    }

    @Test void shutdownAdmissionPreservesDefinitionsForSaveAndStopsDispatch() {
        NPC npc = new Group().create();
        var calls = new AtomicInteger();
        Consumer<NPCSpawnEvent>[] listener = new Consumer[1];
        var commands = new CommandListExecutor(List.of("say saved")) {
            @Override public void accept(NPC target) { calls.incrementAndGet(); }
        };
        var action = new CommandEventAction(NPCSpawnEvent.class, commands, (type, consumer) -> listener[0] = consumer);
        new Template(ResourceLocation.parse("test:shutdown"), List.of(action)).apply(npc);
        var before = snapshot(npc).getValuesDeep();
        CommandEventAction.beginShutdown();
        listener[0].accept(new NPCSpawnEvent(npc, null, net.citizensnpcs.api.event.SpawnReason.RESPAWN));
        assertEquals(0, calls.get());
        assertEquals(before, snapshot(npc).getValuesDeep());
        assertThrows(IllegalStateException.class, () -> action.accept(npc));
    }

    @Test void failureRegisteringLaterTemplateRollsBackTheWholeReplacement() {
        NPC npc = new Group().create();
        var oldCalls = new AtomicInteger();
        Consumer<NPCSpawnEvent>[] oldListener = new Consumer[1];
        var old = new CommandEventAction(NPCSpawnEvent.class, target -> oldCalls.incrementAndGet(),
                (type, listener) -> oldListener[0] = listener);
        var key = ResourceLocation.parse("test:old");
        new Template(key, List.of(old)).apply(npc);
        var freshCalls = new AtomicInteger();
        Consumer<NPCSpawnEvent>[] freshListener = new Consumer[1];
        var fresh = new CommandEventAction(NPCSpawnEvent.class, target -> freshCalls.incrementAndGet(),
                (type, listener) -> freshListener[0] = listener);
        var rejected = new CommandEventAction(NPCSpawnEvent.class, target -> { },
                (type, listener) -> { throw new IllegalStateException("registration failed"); });
        var replacement = new LinkedHashMap<ResourceLocation, List<CommandEventAction>>();
        replacement.put(key, List.of(fresh));
        replacement.put(ResourceLocation.parse("test:later"), List.of(rejected));
        assertThrows(IllegalStateException.class, () -> CommandEventAction.replaceSubscriptions(replacement, npc));
        oldListener[0].accept(new NPCSpawnEvent(npc, null, net.citizensnpcs.api.event.SpawnReason.RESPAWN));
        freshListener[0].accept(new NPCSpawnEvent(npc, null, net.citizensnpcs.api.event.SpawnReason.RESPAWN));
        assertEquals(1, oldCalls.get()); assertEquals(0, freshCalls.get());
    }
}
