package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.command.CommandContext;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.persistence.DelegatePersistence;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persister;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.api.util.*;
import net.citizensnpcs.commands.AdminCommands;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class SaveIntegrityRuntimeAudit {
    private static final List<NPCRegistry> named = new ArrayList<>();
    private static final List<Probe> probes = new ArrayList<>();
    private static MinecraftServer server;
    private static ServerLevel level;
    private static Citizens plugin;
    private static NPCDataStore original;
    private static int started, passed;
    private static boolean forced, done;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld(); plugin = Citizens.getInstance();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("save-integrity-audit-fixture.txt")), "isolated_fixture");
                check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                original = plugin.getDefaultNPCDataStore();
                CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(Probe.class).withName("saveintegrityprobe"));
                CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(RemovedProbe.class).withName("saveintegrityremoved"));
                level.setChunkForced(0, 0, true); forced = true; started = server.getTickCount();
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            standalone(false); standalone(true);
            batch(); replacementDuringSave(); commandAndReplacement();
            close();
            LoggerFactory.getLogger("citizens").info("[SAVEINTEGRITYAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SAVEINTEGRITYAUDIT] FAILED", failure);
            close();
        }
    }

    private static void standalone(boolean packet) {
        String label = packet ? "packet_" : "ordinary_";
        NPCRegistry registry = registry(new MemoryNPCDataStore());
        NPC npc = registry.createNPC(EntityType.PIG, label + "original");
        if (packet) npc.getOrAddTrait(PacketNPC.class);
        check(npc.spawn(at(packet ? 6 : 4)), label + "spawn");
        Entity entity = npc.getEntity();
        Probe probe = probe(npc);
        npc.getOrAddTrait(RemovedProbe.class).value = "old removal marker";
        npc.getNavigator().getDefaultParameters().speedModifier(1.75F).avoidWater(true);

        MemoryDataKey document = new MemoryDataKey();
        document.setMap("records", new LinkedHashMap<>(Map.of("literal.key", new LinkedHashMap<>())));
        document.setMap("unrelated", Map.of("literal.key", List.of("preserved")));
        DataKey destination = document.getRelative("records").getSubKeys().iterator().next();
        npc.save(destination);
        check(destination.getString("name").equals(npc.getRawName()), label + "ordinary_save");
        check(destination.getDouble("navigator.speedmodifier") == 1.75D && destination.getBoolean("navigator.avoidwater"),
                label + "navigator_included");
        check((!destination.keyExists("traits.spawned") || destination.getBoolean("traits.spawned"))
                && List.of(destination.getString("traitnames").split(",")).contains("packet") == packet,
                label + "spawn_and_transport_saved");
        check(document.getString("global.saveintegrity.shared").equals(probe.shared), label + "namespace_at_document_root");
        check(!destination.keyExists("global") && document.getRaw("records.literal") == null, label + "literal_record_path");
        Map<String, Object> before = document.getValuesDeep();
        npc.setName(label + "updated"); probe.manual = "changed"; probe.shared = label + "changed global";

        probe.failSave = true;
        reject(() -> npc.save(destination), label + "trait_failure_propagates");
        check(before.equals(document.getValuesDeep()), label + "trait_failure_preserves_document");
        reject(() -> npc.saveSnapshot(destination), label + "snapshot_failure_propagates");
        check(before.equals(document.getValuesDeep()), label + "snapshot_failure_preserves_document");
        probe.failSave = false; probe.payload.fail = true;
        reject(() -> npc.save(destination), label + "delegate_failure_propagates");
        check(before.equals(document.getValuesDeep()), label + "delegate_failure_preserves_document");
        check(npc.getEntity() == entity && npc.isSpawned(), label + "failed_save_keeps_live_entity");
        probe.payload.fail = false;

        boolean[] entered = { false };
        probe.callback = () -> {
            if (entered[0]) return;
            entered[0] = true;
            try {
                reject(() -> npc.save(new MemoryDataKey()), label + "recursive_save_rejected");
                reject(() -> npc.saveSnapshot(new MemoryDataKey()), label + "recursive_snapshot_rejected");
            } finally { entered[0] = false; }
        };
        npc.save(destination); probe.callback = null;
        check(destination.getString("name").equals(npc.getRawName()) && document.getString("global.saveintegrity.shared").equals(probe.shared),
                label + "successful_retry_publishes");
        before = document.getValuesDeep();
        probe.retained.setString("manual", "late key edit"); probe.sequence.add("late collection edit");
        probe.retained.getFromRoot("global.saveintegrity").setString("shared", "late global edit");
        check(before.equals(document.getValuesDeep()), label + "retained_writers_are_detached");

        DataKey second = document.copyWithRoot();
        npc.removeTrait(RemovedProbe.class);
        npc.saveSnapshot(new MemoryDataKey());
        npc.save(destination);
        DataKey secondDestination = second.getRelative("records").getSubKeys().iterator().next();
        npc.save(secondDestination);
        removed(destination, label + "first_destination"); removed(secondDestination, label + "second_destination");
        npc.getOrAddTrait(RemovedProbe.class).value = "reattached";
        npc.save(destination);
        check(destination.getString("traits.saveintegrityremoved.value").equals("reattached"), label + "reattached_trait_wins");
        check(List.of(destination.getString("traitnames").split(",")).contains("saveintegrityremoved"), label + "reattached_trait_named");

        before = document.getValuesDeep();
        npc.data().setPersistent(NPC.Metadata.SHOULD_SAVE, false);
        probe.failSave = true;
        npc.save(destination);
        check(before.equals(document.getValuesDeep()), label + "nonpersistent_save_is_noop");
        MemoryDataKey excluded = new MemoryDataKey(); npc.save(excluded);
        check(excluded.getValuesDeep().isEmpty(), label + "nonpersistent_save_does_not_create_record");
        probe.failSave = false; npc.saveSnapshot(excluded);
        check(excluded.getString("name").equals(npc.getRawName()) && excluded.getDouble("navigator.speedmodifier") == 1.75D,
                label + "nonpersistent_snapshot_includes_state");
        NPC clone = npc.copy();
        check(clone.getTraitNullable(Probe.class).shared.equals(probe.shared), label + "copy_loads_namespaced_state");
        check(clone.getNavigator().getDefaultParameters().speedModifier() == 1.75F, label + "copy_loads_navigator");
    }

    private static void batch() {
        MemoryStorage storage = new MemoryStorage();
        InspectingStore store = new InspectingStore(storage);
        NPCRegistry registry = registry(store);
        for (int id : List.of(0, 7, 20)) probe(registry.createNPC(EntityType.PIG, UUID.randomUUID(), id, "Batch" + id));
        List<NPC> ordered = new ArrayList<>(); registry.forEach(ordered::add);
        NPC first = ordered.getFirst(), last = ordered.getLast();
        first.getOrAddTrait(RemovedProbe.class).value = "must be removed on retry";
        last.getOrAddTrait(PacketNPC.class);
        store.storeAll(registry);
        storage.key.setMap("npc.007", storage.key.getRelative("npc.7").getValuesDeep()); storage.key.removeKey("npc.7");
        storage.key.setMap("npc.900", Map.of("name", "Unavailable", "traitnames", "missing", "traits", Map.of("missing", Map.of("literal.key", List.of("kept")))));
        storage.key.setRaw("npc.901", "damaged definition");
        storage.key.setMap("unknown", Map.of("literal.key", List.of("retained")));
        Map<String, Object> before = storage.key.getValuesDeep();
        first.setName("First changed"); first.removeTrait(RemovedProbe.class);
        Probe failing = last.getTraitNullable(Probe.class); failing.failSave = true;
        reject(registry::saveToStore, "batch_later_trait_failure_propagates");
        check(before.equals(storage.key.getValuesDeep()) && storage.flushes == 0, "failed_batch_does_not_publish_or_flush");
        failing.failSave = false; registry.saveToStore();
        check(storage.flushes == 1, "retry_flushes_once");
        removed(record(storage, first), "batch_retry");
        check(record(storage, first).getString("name").equals("First changed"), "retry_publishes_earlier_npc");
        check(storage.key.keyExists("npc.007") && !storage.key.keyExists("npc.7"), "numeric_key_spelling_retained");
        Map<?, ?> previousRecords = (Map<?, ?>) before.get("npc");
        check(previousRecords.get("900").equals(storage.key.getRaw("npc.900")) && previousRecords.get("901").equals(storage.key.getRaw("npc.901")),
                "unloaded_and_malformed_definitions_retained");
        check(before.get("unknown").equals(storage.key.getRaw("unknown")), "unrelated_sections_retained");

        NPC noSave = registry.createNPC(EntityType.PIG, "Excluded"); noSave.data().set(NPC.Metadata.SHOULD_SAVE, false);
        store.store(noSave); store.storeAll(registry);
        check(storage.key.getRaw("npc." + noSave.getId()) == null, "batch_skips_new_nonpersistent_record");
        first.getTraitNullable(Probe.class).callback = () -> {
            reject(store::saveToDiskImmediate, "native_callback_flush_rejected");
            reject(store::reloadFromSource, "native_callback_reload_rejected");
            reject(() -> store.storeAll(registry), "native_callback_store_rejected");
            reject(() -> store.clearData(first), "native_callback_delete_rejected");
            reject(() -> registry.createNPC(EntityType.PIG, "Recursive allocation"), "native_callback_allocation_rejected");
        };
        registry.saveToStore(); first.getTraitNullable(Probe.class).callback = null;
        check(storage.flushes == 2, "native_callback_guard_allows_outer_flush");

        NPCRegistry restored = registry(new MemoryNPCDataStore());
        // Exclude the intentionally malformed/unavailable records from this independent load check.
        MemoryStorage restoredStorage = new MemoryStorage();
        for (NPC npc : ordered) restoredStorage.key.setMap("npc." + npc.getId(), record(storage, npc).getValuesDeep());
        restoredStorage.key.setMap("global", storage.key.getRelative("global").getValuesDeep());
        new SimpleNPCDataStore(restoredStorage).loadInto(restored);
        for (NPC npc : ordered) {
            NPC loaded = restored.getById(npc.getId());
            check(loaded != null && loaded.getTraitNullable(Probe.class).shared.equals(storage.key.getString("global.saveintegrity.shared")),
                    "restored_namespaced_field_" + npc.getId());
        }
    }

    private static void replacementDuringSave() {
        MemoryStorage storage = new MemoryStorage();
        InspectingStore store = new InspectingStore(storage);
        NPCRegistry registry = registry(store);
        NPC previous = registry.createNPC(EntityType.PIG, "Previous instance");
        Probe trigger = probe(previous); registry.saveToStore();
        Map<String, Object> before = storage.key.getValuesDeep();
        NPC[] replacement = { null };
        trigger.callback = () -> replacement[0] = registry.createNPC(EntityType.PIG, previous.getUniqueId(), previous.getId(), "Replacement instance");
        reject(registry::saveToStore, "same_identity_replacement_aborts_save");
        trigger.callback = null;
        check(before.equals(storage.key.getValuesDeep()) && storage.flushes == 1, "obsolete_snapshot_is_not_published");
        check(registry.getById(previous.getId()) == replacement[0] && replacement[0] != previous, "external_live_replacement_is_retained");
        reject(() -> store.store(previous), "stale_instance_cannot_save");
        previous.destroy(); registry.saveToStore();
        check(record(storage, replacement[0]).getString("name").equals("Replacement instance"), "replacement_instance_can_save_on_retry");
    }

    private static void commandAndReplacement() throws Exception {
        MemoryStorage storage = new MemoryStorage();
        InspectingStore store = new InspectingStore(storage);
        plugin.setDefaultNPCDataStore(store);
        NPCRegistry registry = CitizensAPI.getNPCRegistry();
        NPC ordinary = registry.createNPC(EntityType.PIG, "SavingWorld"); probe(ordinary);
        NPC virtual = registry.createNPC(EntityType.PIG, "SavingVirtual"); probe(virtual); virtual.getOrAddTrait(PacketNPC.class);
        check(ordinary.spawn(at(8)) && virtual.spawn(at(10)), "default_npcs_spawn");
        Entity ordinaryEntity = ordinary.getEntity(), virtualEntity = virtual.getEntity();
        registry.saveToStore(); int flushes = storage.flushes;
        Map<String, Object> before = storage.key.getValuesDeep();
        ordinary.setName("UpdatedWorld"); Probe failing = virtual.getTraitNullable(Probe.class); failing.failSave = true;

        CapturedSource output = new CapturedSource();
        CommandSourceStack source = server.createCommandSourceStack().withSource(output);
        AdminCommands commands = new AdminCommands(plugin);
        CommandContext context = new CommandContext(source, new String[] { "citizens", "save" });
        reject(() -> commands.save(context, source, null), "save_command_propagates_trait_failure");
        reject(() -> server.getCommands().getDispatcher().execute("citizens save", source),
                com.mojang.brigadier.exceptions.CommandSyntaxException.class, "registered_save_command_fails");
        String saved = Messaging.stripColor(Messaging.tr(Messages.CITIZENS_SAVED));
        check(output.messages.stream().noneMatch(text -> text.contains(saved)), "save_command_does_not_report_false_success");
        check(before.equals(storage.key.getValuesDeep()) && storage.flushes == flushes, "save_command_preserves_previous_snapshot");

        MemoryStorage replacementStorage = new MemoryStorage();
        seed(replacementStorage, 60, "Replacement");
        InspectingStore replacement = new InspectingStore(replacementStorage);
        reject(() -> plugin.setDefaultNPCDataStore(replacement), "switch_aborts_on_native_trait_failure");
        check(plugin.getDefaultNPCDataStore() == store && CitizensAPI.getNPCRegistry() == registry, "failed_switch_preserves_binding");
        check(ordinary.getEntity() == ordinaryEntity && virtual.getEntity() == virtualEntity && ordinary.isSpawned() && virtual.isSpawned(),
                "failed_switch_preserves_live_transports");
        check(before.equals(storage.key.getValuesDeep()) && storage.flushes == flushes, "failed_switch_preserves_store");
        check(replacement.loaded != null && !replacement.loaded.iterator().hasNext(), "failed_switch_retires_provisional_npcs");
        reject(() -> replacement.loaded.createNPC(EntityType.PIG, "Stale candidate"), "failed_candidate_cannot_create");

        failing.failSave = false; output.messages.clear();
        // Successful retry goes through the registered command root as well as the direct failure path above.
        server.getCommands().getDispatcher().execute("citizens save", source);
        check(output.messages.stream().anyMatch(text -> text.contains(saved)), "save_command_retry_reports_success");
        check(storage.flushes == flushes + 1 && record(storage, ordinary).getString("name").equals("UpdatedWorld"), "save_command_retry_persists");
        plugin.setDefaultNPCDataStore(replacement);
        check(plugin.getDefaultNPCDataStore() == replacement && CitizensAPI.getNPCRegistry().getById(60) != null, "switch_retry_succeeds");
        check(ordinary.getEntity() == null && virtual.getEntity() == null && ordinaryEntity.isRemoved(), "successful_switch_retires_old_npcs");
        check(storage.key.keyExists("npc." + ordinary.getId()) && storage.key.keyExists("npc." + virtual.getId()), "retirement_keeps_persisted_definitions");
    }

    private static NPCRegistry registry(NPCDataStore store) {
        NPCRegistry result = CitizensAPI.createNamedNPCRegistry("save-integrity-" + named.size(), store);
        named.add(result); return result;
    }
    private static Probe probe(NPC npc) {
        Probe result = npc.getOrAddTrait(Probe.class); result.shared = "shared-" + npc.getUniqueId(); probes.add(result); return result;
    }
    private static DataKey record(MemoryStorage storage, NPC npc) {
        String id = npc.getId() == 7 && storage.key.keyExists("npc.007") ? "007" : Integer.toString(npc.getId());
        return storage.key.getRelative("npc." + id);
    }
    private static void seed(MemoryStorage storage, int id, String name) {
        DataKey key = storage.key.getRelative("npc." + id);
        key.setString("name", name); key.setString("uuid", UUID.randomUUID().toString());
        key.setString("traitnames", "type,spawned"); key.setString("traits.type", "PIG"); key.setBoolean("traits.spawned", false);
    }
    private static void removed(DataKey key, String label) {
        check(!key.keyExists("traits.saveintegrityremoved"), label + "_removed_payload");
        check(!List.of(key.getString("traitnames").split(",")).contains("saveintegrityremoved"), label + "_removed_name");
    }
    private static Location at(int x) { return new Location(level, x, -58, 4); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[SAVEINTEGRITYAUDIT] PASS {}", label);
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action, String label) {
        reject(action, IllegalStateException.class, label);
    }
    private static void reject(Action action, Class<? extends Throwable> type, String label) {
        Throwable caught = null;
        try { action.run(); } catch (Throwable failure) { caught = failure; }
        if (!type.isInstance(caught)) throw new AssertionError(label, caught);
        check(true, label);
    }
    private static void close() {
        if (done) return;
        done = true;
        try {
            for (Probe probe : probes) { probe.failSave = false; probe.payload.fail = false; probe.callback = null; }
            for (NPCRegistry registry : named) {
                for (NPC npc : registry.sorted()) npc.destroy();
                CitizensAPI.removeNamedNPCRegistry(registry.getName());
            }
            if (original != null && plugin.getDefaultNPCDataStore() != original) plugin.setDefaultNPCDataStore(original);
            if (forced) level.setChunkForced(0, 0, false);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[SAVEINTEGRITYAUDIT] FAILED cleanup", failure);
        }
        server.halt(false);
    }

    public static final class Probe extends Trait {
        @Persist(value = "shared", namespace = "saveintegrity") String shared = "";
        @Persist @DelegatePersistence(ProbePersister.class) Payload payload = new Payload();
        String manual = "original";
        List<String> sequence = new ArrayList<>();
        DataKey retained;
        boolean failSave;
        Runnable callback;
        public Probe() { super("saveintegrityprobe"); }
        @Override public void save(DataKey key) {
            retained = key; key.setString("manual", manual); key.setRaw("sequence", sequence);
            if (callback != null) callback.run();
            if (failSave) throw new IllegalStateException("synthetic trait save failure");
        }
    }
    public static final class RemovedProbe extends Trait {
        @Persist String value = "";
        public RemovedProbe() { super("saveintegrityremoved"); }
    }
    public static final class Payload { String value = "original"; boolean fail; }
    public static final class ProbePersister implements Persister<Payload> {
        public ProbePersister() { }
        @Override public Payload create(DataKey key) { Payload value = new Payload(); value.value = key.getString("value"); return value; }
        @Override public void save(Payload value, DataKey key) {
            key.setString("value", value.value);
            if (value.fail) throw new IllegalStateException("synthetic delegate save failure");
        }
    }
    private static final class CapturedSource implements CommandSource {
        final List<String> messages = new ArrayList<>();
        @Override public void sendSystemMessage(Component message) { messages.add(message.getString()); }
        @Override public boolean acceptsSuccess() { return true; }
        @Override public boolean acceptsFailure() { return true; }
        @Override public boolean shouldInformAdmins() { return false; }
    }
    private static final class MemoryStorage implements Storage {
        final MemoryDataKey key = new MemoryDataKey(new LinkedHashMap<>());
        int flushes;
        @Override public DataKey getKey(String path) { return key.getRelative(path); }
        @Override public boolean load() { return true; }
        @Override public void save() { flushes++; }
        @Override public void saveAsync() { flushes++; }
    }
    private static final class InspectingStore extends SimpleNPCDataStore {
        NPCRegistry loaded;
        InspectingStore(MemoryStorage storage) { super(storage); }
        @Override public void loadInto(NPCRegistry registry) { loaded = registry; super.loadInto(registry); }
    }
}
