package net.citizensnpcs.trait.waypoint;

import java.util.IdentityHashMap;
import java.util.Map;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.schedulers.SchedulerTask;
import net.citizensnpcs.trait.waypoint.triggers.DelayTrigger;
import net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger;
import net.minecraft.world.entity.Entity;

/** One arrival sequence per NPC, with a stable trigger snapshot and an owned temporary pause. */
@net.neoforged.fml.common.EventBusSubscriber(modid = "citizens")
public final class WaypointExecution {
    private static final Map<NPC, Run> active = new IdentityHashMap<>();
    private WaypointExecution() { }

    @net.neoforged.bus.api.SubscribeEvent
    public static void removed(net.citizensnpcs.api.event.NPCRemoveEvent event) {
        Run run = active.get(event.getNPC());
        if (run != null) run.cancel();
    }

    @net.neoforged.bus.api.SubscribeEvent
    public static void stopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for (Run run : java.util.List.copyOf(active.values())) run.cancel();
        active.clear();
    }

    static void begin(NPC npc, Waypoint waypoint) {
        Run prior = active.get(npc);
        if (prior != null) prior.cancel();
        if (active.containsKey(npc)) return;
        if (!currentNPC(npc)) return;
        Run run = new Run(npc, waypoint);
        active.put(npc, run);
        run.advance();
    }

    static void cancel(NPC npc, WaypointProvider provider) {
        Run run = active.get(npc);
        if (run != null && run.provider == provider) run.cancel();
    }

    static void cancelWaiting(NPC npc, WaypointProvider provider) {
        Run run = active.get(npc);
        if (run != null && run.provider == provider && run.task != null) run.cancel();
    }

    private static boolean currentNPC(NPC npc) {
        return npc != null && npc.isSpawned() && npc.getOwningRegistry().getByUniqueId(npc.getUniqueId()) == npc
                && npc.getOwningRegistry().getById(npc.getId()) == npc;
    }

    private static final class Run {
        final NPC npc;
        final Waypoints trait;
        final WaypointProvider provider;
        final long ownerRevision, routeRevision;
        final WaypointTrigger[] triggers;
        final Location location;
        SchedulerTask task;
        int index;
        long pauseRevision;
        boolean ownsPause;

        Run(NPC npc, Waypoint waypoint) {
            this.npc = npc;
            trait = npc.getTraitNullable(Waypoints.class);
            provider = trait == null ? null : trait.getCurrentProvider();
            ownerRevision = trait == null ? 0 : trait.getRevision();
            routeRevision = provider == null ? 0 : provider.getRouteRevision();
            triggers = waypoint.getTriggers().toArray(WaypointTrigger[]::new);
            location = waypoint.getLocation();
        }

        boolean current() {
            return active.get(npc) == this && currentNPC(npc) && npc.getTraitNullable(Waypoints.class) == trait
                    && (trait == null || trait.isCurrent() && trait.getRevision() == ownerRevision && trait.getCurrentProvider() == provider)
                    && (provider == null || provider.getRouteRevision() == routeRevision);
        }

        void advance() {
            try {
                while (current() && index < triggers.length) {
                    WaypointTrigger trigger = triggers[index++];
                    trigger.onWaypointReached(npc, location.clone());
                    if (!current()) break;
                    if (!(trigger instanceof DelayTrigger delay) || delay.getDelay() <= 0) continue;
                    boolean wasPaused = provider != null && provider.isPaused();
                    if (provider != null && !wasPaused) {
                        pauseRevision = provider.getPauseRevision() < 0 ? -1 : provider.getPauseRevision() + 1;
                        ownsPause = true;
                        provider.setPaused(true);
                    }
                    if (!ownsPause) pauseRevision = provider == null ? 0 : provider.getPauseRevision();
                    if (!current()) break;
                    Entity entity = npc.getEntity();
                    task = CitizensAPI.getScheduler().runTaskLater(() -> {
                        task = null;
                        if (!current() || npc.getEntity() != entity
                                || provider != null && provider.getPauseRevision() != pauseRevision) { cancel(); return; }
                        releasePause();
                        advance();
                    }, delay.getDelay());
                    return;
                }
                cancel();
            } catch (RuntimeException | Error failure) { cancel(); throw failure; }
        }

        void releasePause() {
            boolean release = ownsPause;
            ownsPause = false;
            if (release && provider.getPauseRevision() == pauseRevision && provider.isPaused()) provider.setPaused(false);
        }

        void cancel() {
            if (active.get(npc) == this) active.remove(npc);
            if (task != null) { task.cancel(); task = null; }
            releasePause();
        }
    }
}
