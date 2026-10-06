package net.citizensnpcs.audit;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.citizensnpcs.Citizens;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCDespawnEvent;
import net.citizensnpcs.api.event.NPCNeedsRespawnEvent;
import net.citizensnpcs.api.npc.*;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;
import net.citizensnpcs.api.util.schedulers.Schedulers;
import net.citizensnpcs.api.util.schedulers.adapter.NeoForgeScheduler;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.LoggerFactory;

@EventBusSubscriber(modid = "citizens")
public final class ShutdownRuntimeAudit {
    private static Citizens plugin;
    private static MinecraftServer server;
    private static ServerLevel level;
    private static final List<NPC> npcs = new ArrayList<>();
    private static final List<Entity> entities = new ArrayList<>();
    private static final List<NPCRegistry> registries = new ArrayList<>();
    private static final List<Probe> probes = new ArrayList<>();
    private static final List<SchedulerTask> tasks = new ArrayList<>();
    private static Store store;
    private static NeoForgeScheduler scheduler;
    private static Object listener;
    private static net.citizensnpcs.api.npc.templates.CommandEventAction templateAction;
    private static int templateCalls;
    private static int started, prepared, passed;
    private static boolean forced, armed, failed, verified;

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void despawn(NPCDespawnEvent event) {
        if (armed && npcs.contains(event.getNPC())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(ServerTickEvent.Post event) {
        if (armed || failed) return;
        server = event.getServer(); level = server.overworld(); plugin = Citizens.getInstance();
        try {
            if (!forced) {
                check(Files.isRegularFile(Path.of("shutdown-audit-fixture.txt")), "isolated_fixture");
                check(!plugin.getNPCRegistry().iterator().hasNext(), "empty_fixture");
                level.setChunkForced(0, 0, true); forced = true; started = server.getTickCount();
            }
            if (server.getTickCount() - started > 400) throw new AssertionError("timeout");
            if (!level.isPositionEntityTicking(new BlockPos(4, -58, 4))) return;
            if (prepared == 0) {
                setup(); prepared = server.getTickCount();
            } else if (server.getTickCount() - prepared >= 3) {
                check(probes.stream().allMatch(p -> p.events > 0), "trait_listeners_live_before_stop");
                armed = true; store.fail = true; server.halt(false);
            }
        } catch (Throwable failure) { fail(failure); server.halt(false); }
    }

    private static void setup() throws Exception {
        store = new Store(); plugin.setDefaultNPCDataStore(store);
        registries.add(plugin.getNPCRegistry());
        registries.add(plugin.createNamedNPCRegistry("shutdown-named", new MemoryNPCDataStore()));
        registries.add(plugin.createAnonymousNPCRegistry(new MemoryNPCDataStore()));
        registries.add(plugin.getTemporaryNPCRegistry());
        for (int i = 0; i < registries.size(); i++) {
            NPC npc = registries.get(i).createNPC(EntityType.PIG, "Shutdown" + i);
            if (i % 2 == 1) npc.getOrAddTrait(PacketNPC.class);
            check(npc.spawn(new Location(level, 4 + i * 2, -58, 4)), "spawn_registry_" + i);
            npcs.add(npc); entities.add(npc.getEntity());
            Probe probe = new Probe(); probe.fail = i == 0; npc.addTrait(probe); probes.add(probe);
        }
        NPC queued = registries.get(1).createNPC(EntityType.PIG, "QueuedShutdown");
        plugin.getDefaultNPCSelector().select(server.createCommandSourceStack(), npcs.get(2));
        NeoForge.EVENT_BUS.post(new NPCNeedsRespawnEvent(queued, new Location(level, 640, -58, 4)));
        var field = Citizens.class.getDeclaredField("eventListen"); field.setAccessible(true); listener = field.get(plugin);
        scheduler = plugin.getScheduler();
        tasks.add(scheduler.runTaskLater(() -> { throw new AssertionError("pending_sync_task_ran"); }, 10000));
        tasks.add(scheduler.runTaskLaterAsynchronously(() -> { throw new AssertionError("pending_async_task_ran"); }, 10000));
        SessionShutdownChecks.prepare(server);
        templateAction = new net.citizensnpcs.api.npc.templates.CommandEventAction(TemplateProbeEvent.class,
                npc -> templateCalls++);
        templateAction.accept(npcs.get(0));
        // A superseded registry can leave an action reachable only through the event bus.
        new net.citizensnpcs.api.npc.templates.CommandEventAction(TemplateProbeEvent.class,
                npc -> templateCalls++).accept(npcs.get(0));
        NeoForge.EVENT_BUS.post(new TemplateProbeEvent(npcs.get(0)));
        check(templateCalls == 2, "template_event_actions_live_before_stop");
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopping(ServerStoppingEvent event) {
        if (failed) return;
        try {
            check(armed, "shutdown_was_requested");
            NeoForge.EVENT_BUS.post(new TemplateProbeEvent(npcs.get(0)));
            check(templateCalls == 2, "all_template_event_actions_stopped");
            reject(() -> templateAction.accept(npcs.get(0)), "retired_template_action_rejects_application");
            reject(() -> new net.citizensnpcs.api.npc.templates.CommandEventAction(TemplateProbeEvent.class,
                    npc -> templateCalls++).accept(npcs.get(0)), "shutdown_rejects_new_template_subscriptions");
            check(store.saves == 1, "save_attempted_once_despite_failure");
            check(store.clears == 0, "retirement_does_not_delete_definitions");
            check(!plugin.isEnabled() && plugin.getNPCRegistry() == null && plugin.getTemporaryNPCRegistry() == null,
                    "default_and_temporary_bindings_released");
            check(!plugin.getNPCRegistries().iterator().hasNext() && plugin.getNamedNPCRegistry("shutdown-named") == null,
                    "all_registry_bindings_released");
            for (int i = 0; i < npcs.size(); i++) {
                check(npcs.get(i).getEntity() == null && entities.get(i).isRemoved(), "entity_released_despite_veto_" + i);
                check(!registries.get(i).iterator().hasNext() && probes.get(i).removals == 1, "registry_and_trait_retired_" + i);
                NPCRegistry registry = registries.get(i);
                reject(() -> registry.createNPC(EntityType.PIG, "TooLate"), "retired_registry_rejects_creation_" + i);
            }
            check(plugin.getScheduler() == null && Schedulers.getOrNull() == null, "scheduler_bindings_released");
            check(tasks.stream().allMatch(SchedulerTask::isCancelled), "pending_task_handles_cancelled");
            reject(() -> scheduler.runTask(() -> {}), "retained_scheduler_rejects_sync");
            reject(() -> scheduler.runTaskAsynchronously(() -> {}), "retained_scheduler_rejects_async");
            reject(() -> plugin.createNamedNPCRegistry("late", new MemoryNPCDataStore()), "new_named_registry_rejected");
            reject(() -> plugin.createAnonymousNPCRegistry(new MemoryNPCDataStore()), "new_anonymous_registry_rejected");
            reject(plugin::storeNPCs, "shutdown_save_reentry_rejected");
            var field = listener.getClass().getDeclaredField("toRespawn"); field.setAccessible(true);
            check(((com.google.common.collect.Multimap<?, ?>) field.get(listener)).isEmpty(), "pending_respawns_cleared");
            reject(CitizensAPI::getNPCRegistry, "API_released");
            check(plugin.getDefaultNPCSelector().getSelected(server.createCommandSourceStack()) == null,
                    "anonymous_selection_reference_released");
            for (String label : SessionShutdownChecks.verify()) check(true, label);
            plugin.onServerStopping(event);
            check(store.saves == 1 && probes.stream().allMatch(p -> p.removals == 1), "duplicate_stop_is_idempotent");
            verified = true;
        } catch (Throwable failure) { fail(failure); }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopped(ServerStoppedEvent event) {
        if (failed) return;
        try {
            check(verified && plugin.getServer() == null, "server_reference_released");
            LoggerFactory.getLogger("citizens").info("[SHUTDOWNAUDIT] COMPLETE {} checks", passed);
        } catch (Throwable failure) { fail(failure); }
    }
    private static void reject(Runnable action, String label) {
        try { action.run(); } catch (IllegalStateException | java.util.concurrent.RejectedExecutionException expected) {
            check(true, label); return;
        }
        throw new AssertionError(label);
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        passed++; LoggerFactory.getLogger("citizens").info("[SHUTDOWNAUDIT] PASS {}", label);
    }
    private static void fail(Throwable failure) {
        failed = true; LoggerFactory.getLogger("citizens").error("[SHUTDOWNAUDIT] FAILED", failure);
    }
    public static final class TemplateProbeEvent extends net.citizensnpcs.api.event.NPCEvent {
        public TemplateProbeEvent(NPC npc) { super(npc); }
    }

    public static final class Probe extends Trait {
        int events, removals; boolean fail;
        public Probe() { super("shutdownprobe"); }
        @SubscribeEvent public void tick(ServerTickEvent.Post event) { events++; }
        @Override public void onRemove(RemoveReason reason) {
            removals++;
            reject(() -> plugin.createAnonymousNPCRegistry(new MemoryNPCDataStore()), "cleanup_cannot_reopen_registry_" + npc.getId());
            if (fail) throw new IllegalStateException("expected shutdown trait cleanup failure");
        }
    }
    private static final class Store extends MemoryNPCDataStore {
        boolean fail; int saves, clears;
        @Override public void clearData(NPC npc) { clears++; }
        @Override public void storeAll(NPCRegistry registry) {
            saves++;
            if (fail) {
                plugin.removeNamedNPCRegistry("shutdown-named");
                throw new IllegalStateException("expected shutdown save failure");
            }
        }
    }
}
