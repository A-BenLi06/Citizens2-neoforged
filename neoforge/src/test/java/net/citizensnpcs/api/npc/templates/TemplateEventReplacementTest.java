package net.citizensnpcs.api.npc.templates;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import net.citizensnpcs.api.event.NPCEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import net.minecraft.resources.ResourceLocation;

class TemplateEventReplacementTest {
    private static final ResourceLocation KEY = ResourceLocation.fromNamespaceAndPath("test", "replace");
    @BeforeEach void start() { CommandEventAction.start(); }
    @AfterEach void stop() { CommandEventAction.shutdown(); }
    private static class Event extends NPCEvent { Event(NPC npc) { super(npc); } }
    private static class TargetGroup {
        final Map<Integer, NPC> npcs = new HashMap<>();
        final NPCRegistry registry = (NPCRegistry) Proxy.newProxyInstance(NPCRegistry.class.getClassLoader(),
                new Class<?>[]{NPCRegistry.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getById" -> npcs.get(args[0]);
                    case "getByUniqueId" -> npcs.values().stream().filter(npc -> npc.getUniqueId().equals(args[0])).findFirst().orElse(null);
                    default -> throw new AssertionError(method.getName());
                });
        NPC create() {
            return create(UUID.randomUUID());
        }
        NPC create(UUID uuid) {
            int id = npcs.size();
            NPC npc = (NPC) Proxy.newProxyInstance(NPC.class.getClassLoader(), new Class<?>[]{NPC.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "getOwningRegistry" -> registry;
                        case "getId" -> id;
                        case "getUniqueId" -> uuid;
                        case "isSpawned" -> false;
                        default -> throw new AssertionError(method.getName());
                    });
            npcs.put(id, npc); return npc;
        }
    }
    private static class Hook {
        Consumer<Event> listener;
        int calls, registrations, removals;
        boolean reject;
        final CommandEventAction action = new CommandEventAction(Event.class, npc -> calls++,
                (type, consumer) -> {
                    if (reject) throw new IllegalStateException("registration rejected");
                    registrations++; listener = consumer;
                }, consumer -> { assertSame(listener, consumer); removals++; });
        void fire(NPC npc) { if (listener != null) listener.accept(new Event(npc)); }
    }

    @Test void reapplicationReplacesOnlyTheSameTemplateOnTheSameNpc() {
        var group = new TargetGroup(); NPC first = group.create(), second = group.create();
        var old = new Hook(); var replacement = new Hook(); var other = new Hook();
        var original = new Template(KEY, List.of(old.action));
        original.apply(first); original.apply(second);
        new Template(ResourceLocation.fromNamespaceAndPath("test", "other"), List.of(other.action)).apply(first);
        var updated = new Template(KEY, List.of(replacement.action));
        updated.apply(first); updated.apply(first);
        old.fire(first); old.fire(second); replacement.fire(first); replacement.fire(second); other.fire(first);
        assertEquals(1, old.calls); assertEquals(1, replacement.calls); assertEquals(1, other.calls);
        assertEquals(1, replacement.registrations);
        assertEquals(0, old.removals);
        new Template(KEY, List.of()).apply(first);
        replacement.fire(first); other.fire(first);
        assertEquals(1, replacement.calls); assertEquals(2, other.calls);
        assertEquals(1, replacement.removals);
        new Template(KEY, List.of()).apply(second);
        assertEquals(1, old.removals);
    }

    @Test void failedOrdinaryActionLeavesPreviousEventsAndDoesNotRegisterNewOnes() {
        NPC target = new TargetGroup().create(); var old = new Hook(); var replacement = new Hook();
        new Template(KEY, List.of(old.action)).apply(target);
        var failed = new Template(KEY, List.of(replacement.action, npc -> { throw new IllegalStateException("action failed"); }));
        assertThrows(IllegalStateException.class, () -> failed.apply(target));
        old.fire(target); replacement.fire(target);
        assertEquals(1, old.calls); assertEquals(0, replacement.registrations);
    }

    @Test void rejectedRegistrationRollsBackAddedEventsAndCanRetry() {
        NPC target = new TargetGroup().create(); var old = new Hook(); var first = new Hook(); var second = new Hook();
        new Template(KEY, List.of(old.action)).apply(target);
        second.reject = true;
        var updated = new Template(KEY, List.of(first.action, second.action));
        assertThrows(IllegalStateException.class, () -> updated.apply(target));
        old.fire(target); first.fire(target);
        assertEquals(1, old.calls); assertEquals(0, first.calls); assertEquals(1, first.removals);
        second.reject = false; updated.apply(target);
        old.fire(target); first.fire(target); second.fire(target);
        assertEquals(1, old.calls); assertEquals(1, first.calls); assertEquals(1, second.calls);
        assertEquals(1, old.removals);
    }

    @Test void candidateReapplicationIsNotOverwrittenByRegistryPublication() {
        for (boolean removeEvents : List.of(false, true)) {
            var oldGroup = new TargetGroup(); var candidateGroup = new TargetGroup();
            NPC oldNpc = oldGroup.create(), candidate = candidateGroup.create(oldNpc.getUniqueId());
            var old = new Hook(); var replacement = new Hook();
            new Template(KEY, List.of(old.action)).apply(oldNpc);
            try (var transfer = CommandEventAction.prepareRegistryTransfer(oldGroup.registry, candidateGroup.registry)) {
                new Template(KEY, removeEvents ? List.of() : List.of(replacement.action)).apply(candidate);
                transfer.commit();
            }
            old.fire(candidate); replacement.fire(candidate);
            assertEquals(0, old.calls);
            assertEquals(removeEvents ? 0 : 1, replacement.calls);
        }
    }
}
