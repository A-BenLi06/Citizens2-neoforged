package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCCreateEvent;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitInfo;
import net.citizensnpcs.api.trait.trait.CurrentLocation;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.trait.PacketNPC;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class StoreSwitchRuntimeAudit {
    private static final String phase = System.getProperty("citizens.store-switch.audit.phase", "runtime");
    private static final List<NPC> failedCandidates = new ArrayList<>();
    private static Citizens plugin;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static NPCDataStore original;
    private static RecordingStore startup, first, replacement;
    private static NPCRegistry previous, named;
    private static NPC world, virtual, delayed, unrelated, temporary;
    private static NPC queuedOld, queuedOther;
    private static UUID missingRedirect;
    private static Entity oldWorldEntity;
    private static LifecycleProbe probe;
    private static int started = -1, switched, reloaded, passed, probeTicks;
    private static boolean done, forced, forcedQueued, tickSwitchQueued, tickSwitched, staleTick;
    private static Throwable startupFailure;
    private static Consumer<NPCCreateEvent> creating;
    private static Consumer<NPCDespawnEvent> despawning;

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void early(ServerStartingEvent event) {
        if (!phase.equals("early")) return;
        configureStartup(false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void late(ServerStartingEvent event) {
        if (!phase.equals("late")) return;
        configureStartup(true);
    }

    private static void configureStartup(boolean throughApi) {
        try {
            startup = new RecordingStore(100);
            startup.seed(41, "StartupStore");
            if (throughApi) CitizensAPI.setDefaultNPCDataStore(startup);
            else Citizens.getInstance().setDefaultNPCDataStore(startup);
            check(startup.loads == 0, "startup_load_waits_for_server_started");
        } catch (Throwable failure) { startupFailure = failure; }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void created(NPCCreateEvent event) { if (creating != null) creating.accept(event); }
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void despawned(NPCDespawnEvent event) { if (despawning != null) despawning.accept(event); }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (done) return;
        server = event.getServer(); level = server.overworld(); plugin = Citizens.getInstance();
        try {
            if (startupFailure != null) throw new AssertionError("startup_configuration", startupFailure);
            if (!forced) {
                check(Files.isRegularFile(Path.of("store-switch-audit-fixture.txt")), "isolated_fixture");
                original = plugin.getDefaultNPCDataStore();
                if (startup != null) {
                    check(original == startup && startup.loads == 1, "startup_uses_custom_store_once");
                    check(CitizensAPI.getNPCRegistry().getById(41) != null, "startup_restores_custom_record");
                    check(startup.stores == 0 && startup.flushes == 0, "startup_does_not_overwrite_unloaded_source");
                } else check(!CitizensAPI.getNPCRegistry().iterator().hasNext(), "empty_default_registry");
                CitizensAPI.getTraitFactory().registerTrait(TraitInfo.create(LifecycleProbe.class).withName("storeswitchprobe"));
                level.setChunkForced(0, 0, true); forced = true; started = server.getTickCount();
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("fixture_timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (switched == 0) {
                initialReplacement(); failureAndReentry(); successfulReplacement();
                switched = server.getTickCount(); probeTicks = probe.events;
            } else if (reloaded == 0 && server.getTickCount() - switched >= 4) {
                if (!level.isPositionEntityTicking(new BlockPos(640, -58, 4))) return;
                verifyRetirement();
                NPC current = CitizensAPI.getNPCRegistry().getById(world.getId());
                plugin.reload();
                check(replacement.reloads == 1, "reload_uses_replacement_source");
                check(CitizensAPI.getNPCRegistry().getById(world.getId()) != current, "reload_restores_replacement_instances");
                reloaded = server.getTickCount();
            } else if (reloaded != 0 && !tickSwitchQueued && server.getTickCount() - reloaded >= 4) {
                verifyLiveReplacement();
                cleanupFailure();
                prepareTickSwitch(); tickSwitchQueued = true;
            } else if (tickSwitchQueued && tickSwitched) {
                check(!staleTick, "retired_siblings_do_not_tick_after_callback_switch");
                check(CitizensAPI.getNPCRegistry().getById(1700) != null, "tick_callback_publishes_replacement");
                close();
                LoggerFactory.getLogger("citizens").info("[STORESWITCHAUDIT] COMPLETE {} checks phase={}", passed, phase);
            }
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[STORESWITCHAUDIT] FAILED phase=" + phase, failure);
            close();
        }
    }

    private static void initialReplacement() {
        NPCRegistry initial = CitizensAPI.getNPCRegistry();
        first = new RecordingStore(301); first.seed(300, "OriginalWorld");
        CitizensAPI.setDefaultNPCDataStore(first);
        previous = CitizensAPI.getNPCRegistry();
        check(previous != initial, "replacement_has_own_registry");
        check(plugin.getDefaultNPCDataStore() == first && first.loadedRegistry == previous, "store_and_registry_bound_together");
        check(first.loads == 1 && first.reloads == 0, "setter_loads_provided_snapshot_once");
        world = previous.getById(300);
        check(world != null && world.spawn(at(4)), "custom_record_spawns");
        oldWorldEntity = world.getEntity();
        probe = new LifecycleProbe(); world.addTrait(probe);
        virtual = previous.createNPC(EntityType.PIG, "OriginalVirtual");
        virtual.getOrAddTrait(PacketNPC.class);
        virtual.addTrait(new ClickRedirectTrait(world));
        check(virtual.getId() == 301 && virtual.spawn(at(6)), "allocation_uses_custom_store");
        delayed = previous.createNPC(EntityType.PIG, "DelayedOld");
        DataKey scheduled = new MemoryDataKey(); world.saveSnapshot(scheduled); delayed.load(scheduled);
        named = CitizensAPI.createNamedNPCRegistry("store-switch-unrelated", new MemoryNPCDataStore());
        unrelated = named.createNPC(EntityType.PIG, world.getUniqueId(), world.getId(), "UnrelatedVirtual");
        unrelated.getOrAddTrait(PacketNPC.class);
        check(unrelated.spawn(at(8)), "unrelated_registry_same_identity_spawns");
        temporary = CitizensAPI.getTemporaryNPCRegistry().createNPC(EntityType.PIG, "UnrelatedTemporary");
        check(temporary.spawn(at(10)), "unrelated_temporary_spawns");
        queuedOld = previous.createNPC(EntityType.PIG, UUID.randomUUID(), 320, "QueuedOriginal");
        queuedOld.getOrAddTrait(CurrentLocation.class).setLocation(at(640));
        queuedOther = named.createNPC(EntityType.PIG, queuedOld.getUniqueId(), 320, "QueuedUnrelated");
        queuedOther.getOrAddTrait(PacketNPC.class);
        queuedOther.getOrAddTrait(CurrentLocation.class).setLocation(at(640));
        NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(queuedOld, at(640)));
        NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(queuedOther, at(640)));
        CitizensAPI.getDefaultNPCSelector().select(server.createCommandSourceStack(), world);
        reject(() -> plugin.setDefaultNPCDataStore(null), IllegalArgumentException.class, "null_store_rejected");
        plugin.setDefaultNPCDataStore(first);
        check(first.loads == 1 && CitizensAPI.getNPCRegistry() == previous, "same_store_is_noop");
    }

    private static void failureAndReentry() throws Exception {
        RecordingStore failed = new RecordingStore(601); failed.seed(600, "FailedSource");
        failed.afterLoad = registry -> {
            check(CitizensAPI.getNPCRegistry() == previous && plugin.getDefaultNPCDataStore() == first,
                    "candidate_load_keeps_previous_binding_visible");
            NPC partial = registry.createNPC(EntityType.PIG, "FailedLiveCandidate");
            check(partial.spawn(at(12)), "failed_candidate_can_be_cleaned_after_spawn");
            failedCandidates.add(partial);
            NPC pending = registry.getById(600);
            DataKey snapshot = new MemoryDataKey(); world.saveSnapshot(snapshot); pending.load(snapshot);
            failedCandidates.add(pending);
            throw new IllegalStateException("synthetic load failure");
        };
        reject(() -> plugin.setDefaultNPCDataStore(failed), IllegalStateException.class, "load_failure_propagates");
        assertPrevious("load_failure");
        check(failed.clears == 0 && failed.records.containsKey(600), "failed_candidate_keeps_recovery_source");
        for (NPC npc : failedCandidates) check(npc.getEntity() == null, "failed_candidate_released_" + npc.getId());

        RecordingStore pending = new RecordingStore(701); pending.seed(700, "PendingReplacement");
        first.failStore = true;
        reject(() -> plugin.setDefaultNPCDataStore(pending), IllegalStateException.class, "save_failure_propagates");
        first.failStore = false; assertPrevious("save_failure");
        first.failFlush = true;
        reject(() -> plugin.setDefaultNPCDataStore(pending), IllegalStateException.class, "flush_failure_propagates");
        first.failFlush = false; assertPrevious("flush_failure");
        first.afterStore = registry -> reject(() -> plugin.setDefaultNPCDataStore(pending), IllegalStateException.class,
                "replacement_rejected_during_direct_registry_save");
        previous.saveToStore(); first.afterStore = null;
        first.afterStore = registry -> reject(() -> plugin.setDefaultNPCDataStore(pending), IllegalStateException.class,
                "replacement_rejected_during_global_save");
        plugin.storeNPCs(); first.afterStore = null;

        AtomicReference<Throwable> offThread = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try { plugin.setDefaultNPCDataStore(pending); }
            catch (Throwable failure) { offThread.set(failure); }
        }, "store-switch-audit-worker");
        worker.start(); worker.join(2000);
        check(!worker.isAlive() && offThread.get() instanceof IllegalStateException, "off_thread_switch_rejected");
        assertPrevious("off_thread_failure");
    }

    private static void successfulReplacement() {
        replacement = new RecordingStore(1000);
        DataKey worldData = new MemoryDataKey(), virtualData = new MemoryDataKey();
        world.saveSnapshot(worldData); virtual.saveSnapshot(virtualData);
        // The helper precedes its parent, and both UUIDs also exist in the registry being replaced.
        replacement.records.put(virtual.getId(), virtualData); replacement.records.put(world.getId(), worldData);
        DataKey queuedData = new MemoryDataKey(); queuedOld.saveSnapshot(queuedData);
        replacement.records.put(queuedOld.getId(), queuedData);
        replacement.afterLoad = registry -> {
            NPC candidate = registry.getById(queuedOld.getId());
            NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(candidate, at(640)));
        };
        replacement.seed(900, "CanceledCandidate");
        replacement.seed(901, "MissingRedirect"); missingRedirect = UUID.randomUUID();
        replacement.records.get(901).setString("traitnames", "type,spawned,clickredirecttrait");
        replacement.records.get(901).setString("traits.clickredirecttrait.uuid", missingRedirect.toString());
        creating = event -> {
            if (event.getNPC().getOwningRegistry() == previous || replacement.loadedRegistry == null
                    || event.getNPC().getOwningRegistry() != replacement.loadedRegistry) return;
            if (event.getNPC().getId() == 900) { event.setCanceled(true); return; }
            if (event.getNPC().getId() != world.getId()) return;
            reject(() -> plugin.setDefaultNPCDataStore(first), IllegalStateException.class, "nested_switch_rejected");
            reject(plugin::storeNPCs, IllegalStateException.class, "global_save_during_load_rejected");
            reject(plugin::reload, IllegalStateException.class, "reload_during_switch_rejected");
            reject(() -> event.getNPC().getOwningRegistry().saveToStore(), IllegalStateException.class,
                    "direct_candidate_save_during_load_rejected");
        };
        despawning = event -> {
            if (event.getNPC() != world) return;
            event.setCanceled(true);
            reject(() -> previous.createNPC(EntityType.PIG, "LateOldCreation"), IllegalStateException.class,
                    "retiring_registry_rejects_callback_creation");
            reject(previous::saveToStore, IllegalStateException.class, "retiring_registry_rejects_callback_save");
            check(!world.spawn(at(4)), "retiring_npc_rejects_respawn");
            delayed.destroy();
        };
        try { CitizensAPI.setDefaultNPCDataStore(replacement); }
        finally { creating = null; despawning = null; }
        NPCRegistry current = CitizensAPI.getNPCRegistry();
        check(current != previous && plugin.getDefaultNPCDataStore() == replacement, "successful_switch_publishes_pair");
        check(current.getById(virtual.getId()).getTraitNullable(ClickRedirectTrait.class).getRedirectToNPC() == current.getById(world.getId()),
                "loaded_redirect_resolves_replacement_parent");
        check(replacement.loads == 1 && replacement.clears == 0 && replacement.records.containsKey(900),
                "canceled_load_preserves_custom_source");
        check(!previous.iterator().hasNext() && previous.getByUniqueId(world.getUniqueId()) == null,
                "old_registry_indexes_are_empty");
        check(first.clears == 0 && first.records.containsKey(world.getId()) && first.records.containsKey(delayed.getId()),
                "retirement_does_not_delete_old_source");
        check(oldWorldEntity.isRemoved() && world.getEntity() == null && virtual.getEntity() == null,
                "despawn_veto_does_not_leave_orphan_entities");
        check(probe.removals == 1, "old_traits_released_once");
        check(CitizensAPI.getDefaultNPCSelector().getSelected(server.createCommandSourceStack()) == current.getById(world.getId()),
                "selection_tracks_replacement_default_registry");
        int oldStores = first.stores, oldFlushes = first.flushes;
        NPC created = current.createNPC(EntityType.PIG, "NewStoreAllocation");
        check(created.getId() == 1000, "new_allocation_uses_replacement_store");
        created.destroy();
        check(replacement.clears == 1 && first.clears == 0, "explicit_delete_uses_replacement_store");
        current.saveToStore(); plugin.storeNPCs();
        ClickRedirectTrait missing = current.getById(901).getTraitNullable(ClickRedirectTrait.class);
        check(missing.getRedirectToNPC() == null && replacement.records.get(901).getString("traits.clickredirecttrait.uuid").equals(missingRedirect.toString()),
                "unavailable_redirect_uuid_survives_save");
        NPC returned = current.createNPC(EntityType.PIG, missingRedirect, 902, "ReturnedRedirectTarget");
        check(missing.getRedirectToNPC() == returned, "unavailable_redirect_recovers_when_target_returns");
        replacement.store(returned);
        check(replacement.stores == 2 && replacement.flushes == 2 && first.stores == oldStores && first.flushes == oldFlushes,
                "direct_and_global_save_use_replacement_store");
        reject(previous::saveToStore, IllegalStateException.class, "stale_registry_cannot_save");
        reject(() -> previous.createNPC(EntityType.PIG, "StaleCreation"), IllegalStateException.class,
                "stale_registry_cannot_allocate");
        level.setChunkForced(40, 0, true); forcedQueued = true;
    }

    private static void assertPrevious(String label) {
        check(CitizensAPI.getNPCRegistry() == previous && plugin.getDefaultNPCDataStore() == first, label + "_binding");
        check(world.getEntity() == oldWorldEntity && world.isSpawned() && virtual.isSpawned(), label + "_live_instances");
        check(CitizensAPI.getDefaultNPCSelector().getSelected(server.createCommandSourceStack()) == world, label + "_selection");
    }

    private static void verifyRetirement() {
        verifyLiveReplacement();
        check(world.getEntity() == null && virtual.getEntity() == null && delayed.getEntity() == null,
                "old_deferred_spawns_remain_retired");
        check(probe.events == probeTicks, "old_trait_event_listener_unregistered");
        for (NPC npc : failedCandidates) {
            check(npc.getEntity() == null && !npc.spawn(at(12)), "failed_candidate_stays_retired_" + npc.getId());
        }
        check(unrelated.isSpawned() && named.getByUniqueId(world.getUniqueId()) == unrelated && temporary.isSpawned(),
                "unrelated_registries_survive_replacement");
        check(queuedOld.getEntity() == null && queuedOther.isSpawned()
                && CitizensAPI.getNPCRegistry().getById(queuedOld.getId()).isSpawned(),
                "same_uuid_pending_spawns_keep_exact_registry_ownership");
    }

    private static void cleanupFailure() {
        NPC doomed = CitizensAPI.getNPCRegistry().createNPC(EntityType.PIG, "FailingCleanup");
        LifecycleProbe failing = new LifecycleProbe(); failing.failRemoval = true; doomed.addTrait(failing);
        check(doomed.spawn(at(12)), "cleanup_failure_fixture_spawns");
        Entity entity = doomed.getEntity();
        RecordingStore next = new RecordingStore(1500); next.seed(1400, "AfterCleanupFailure");
        reject(() -> plugin.setDefaultNPCDataStore(next), IllegalStateException.class, "cleanup_failure_propagates_after_commit");
        check(plugin.getDefaultNPCDataStore() == next && CitizensAPI.getNPCRegistry() == next.loadedRegistry,
                "cleanup_failure_keeps_published_binding");
        check(doomed.getEntity() == null && entity.isRemoved() && failing.removals == 1,
                "cleanup_failure_still_releases_entity_and_trait");
        check(replacement.clears == 1 && replacement.records.containsKey(doomed.getId()),
                "cleanup_failure_preserves_old_store_record");
    }

    private static void prepareTickSwitch() {
        NPCRegistry before = CitizensAPI.getNPCRegistry();
        RecordingStore next = new RecordingStore(1800); next.seed(1700, "FromNpcTick");
        NPC trigger = before.createNPC(EntityType.PIG, "TickSwitchTrigger");
        NPC sibling = before.createNPC(EntityType.PIG, "TickSwitchSibling");
        check(trigger.spawn(at(4)) && sibling.spawn(at(6)), "tick_switch_fixture_spawns");
        sibling.addRunnable(() -> { if (tickSwitched) staleTick = true; });
        trigger.addRunnable(() -> {
            plugin.setDefaultNPCDataStore(next);
            tickSwitched = true;
        });
    }

    private static void verifyLiveReplacement() {
        NPCRegistry current = CitizensAPI.getNPCRegistry();
        NPC restoredWorld = current.getById(world.getId()), restoredVirtual = current.getById(virtual.getId());
        check(restoredWorld != null && restoredVirtual != null && restoredWorld.isSpawned() && restoredVirtual.isSpawned(),
                "replacement_deferred_spawns_complete_" + passed);
        check(level.getEntity(restoredWorld.getEntity().getUUID()) == restoredWorld.getEntity()
                && level.getEntity(restoredVirtual.getEntity().getUUID()) == null, "replacement_preserves_transport_" + passed);
        check(restoredVirtual.getTraitNullable(ClickRedirectTrait.class).getRedirectToNPC() == restoredWorld,
                "forward_redirect_follows_restored_parent_" + passed);
    }

    private static Location at(int x) { return new Location(level, x, -58, 4); }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[STORESWITCHAUDIT] PASS {}", label);
    }
    private interface Action { void run() throws Exception; }
    private static void reject(Action action, Class<? extends Throwable> type, String label) {
        Throwable caught = null;
        try { action.run(); } catch (Throwable failure) { caught = failure; }
        check(type.isInstance(caught), label);
    }

    private static void close() {
        if (done) return;
        done = true; creating = null; despawning = null;
        try {
            if (first != null) { first.failStore = first.failFlush = false; first.afterStore = null; }
            if (named != null) {
                for (NPC npc : named.sorted()) npc.destroy();
                CitizensAPI.removeNamedNPCRegistry(named.getName());
            }
            if (temporary != null) temporary.destroy();
            if (original != null && plugin.getDefaultNPCDataStore() != original) plugin.setDefaultNPCDataStore(original);
            if (forced) level.setChunkForced(0, 0, false);
            if (forcedQueued) level.setChunkForced(40, 0, false);
        } catch (Throwable failure) {
            LoggerFactory.getLogger("citizens").error("[STORESWITCHAUDIT] FAILED cleanup", failure);
        }
        server.halt(false);
    }

    public static final class LifecycleProbe extends Trait {
        int events, removals;
        boolean failRemoval;
        public LifecycleProbe() { super("storeswitchprobe"); }
        @SubscribeEvent public void tick(ServerTickEvent.Post event) { events++; }
        @Override public void onRemove(RemoveReason reason) {
            removals++;
            if (failRemoval) throw new IllegalStateException("synthetic trait cleanup failure");
        }
    }

    private static final class RecordingStore extends MemoryNPCDataStore {
        final Map<Integer, DataKey> records = new LinkedHashMap<>();
        int nextId, loads, reloads, stores, flushes, clears;
        boolean failStore, failFlush;
        NPCRegistry loadedRegistry;
        Consumer<NPCRegistry> afterLoad, afterStore;
        RecordingStore(int nextId) { this.nextId = nextId; }
        void seed(int id, String name) {
            DataKey key = new MemoryDataKey();
            key.setString("name", name); key.setString("uuid", UUID.randomUUID().toString());
            key.setString("traitnames", "type,spawned"); key.setString("traits.type", "PIG"); key.setBoolean("traits.spawned", false);
            records.put(id, key);
        }
        @Override public int createUniqueNPCId(NPCRegistry registry) {
            while (records.containsKey(nextId) || registry.getById(nextId) != null) nextId++;
            return nextId++;
        }
        @Override public void clearData(NPC npc) { clears++; records.remove(npc.getId()); }
        @Override public void loadInto(NPCRegistry registry) {
            loads++; loadedRegistry = registry;
            for (var entry : new ArrayList<>(records.entrySet())) {
                DataKey key = entry.getValue().copy();
                try {
                    NPC npc = registry.createNPC(EntityType.PIG, UUID.fromString(key.getString("uuid")), entry.getKey(), key.getString("name"));
                    npc.load(key);
                } catch (net.citizensnpcs.api.exception.NPCCreationException canceled) {
                    if (entry.getKey() != 900) throw canceled;
                }
            }
            if (afterLoad != null) afterLoad.accept(registry);
        }
        @Override public void reloadFromSource() { reloads++; }
        @Override public void store(NPC npc) {
            DataKey snapshot = new MemoryDataKey(); npc.saveSnapshot(snapshot); records.put(npc.getId(), snapshot);
        }
        @Override public void storeAll(NPCRegistry registry) {
            stores++;
            if (failStore) throw new IllegalStateException("synthetic store failure");
            for (NPC npc : registry.sorted()) store(npc);
            if (afterStore != null) afterStore.accept(registry);
        }
        @Override public void saveToDiskImmediate() {
            flushes++;
            if (failFlush) throw new IllegalStateException("synthetic flush failure");
        }
    }
}
