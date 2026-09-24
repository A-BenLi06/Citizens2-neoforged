package net.citizensnpcs.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.citizensnpcs.api.event.NPCSeenByPlayerEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.TraitEventHandler;
import net.citizensnpcs.npc.NPCRegistries;
import net.citizensnpcs.trait.ClickRedirectTrait;
import net.citizensnpcs.trait.PacketNPC;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** The same Citizens visibility rules for native world trackers and virtual packet entities. */
public final class NPCVisibility {
    // Only contains synchronous event dispatches, not decisions retained across tracking attempts.
    private static final Map<Entity, Set<ServerPlayer>> pairing = new IdentityHashMap<>();

    private NPCVisibility() { }

    /** Cancellable admission before either tracker records a new viewer or sends pairing data. */
    public static boolean allowPairing(Entity entity, ServerPlayer viewer) {
        if (entity.isRemoved()) return false;
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null) return true;
        if (!isVisible(npc, viewer)) return false;
        Set<ServerPlayer> pending = pairing.computeIfAbsent(entity,
                ignored -> Collections.newSetFromMap(new IdentityHashMap<>()));
        if (!pending.add(viewer)) return false;
        PacketNPC packet = npc.getTraitNullable(PacketNPC.class);
        try {
            NPCSeenByPlayerEvent event = new NPCSeenByPlayerEvent(npc, viewer);
            NeoForge.EVENT_BUS.post(event);
            // Listeners may despawn/replace the NPC or change its visibility while deciding.
            return !event.isCanceled() && npc.getEntity() == entity && !entity.isRemoved()
                    && viewer.level() == entity.level() && !viewer.hasDisconnected()
                    && entity.getServer().getPlayerList().getPlayer(viewer.getUUID()) == viewer
                    && npc.getTraitNullable(PacketNPC.class) == packet && isVisible(npc, viewer)
                    && (packet == null || packet.isViewerEligible(viewer));
        } finally {
            pending.remove(viewer);
            if (pending.isEmpty()) pairing.remove(entity);
        }
    }

    /** A current viewer, for supplemental packets that must never precede native pairing. */
    public static boolean isTracked(Entity entity, ServerPlayer viewer) {
        if (!isVisible(entity, viewer) || entity.level() != viewer.level() || viewer.hasDisconnected()) return false;
        if (PacketNPC.isPacketEntity(entity)) return PacketNPC.getInteractionTarget(entity.getId(), viewer) == entity;
        return entity.level() instanceof ServerLevel level
                && level.getServer().getPlayerList().getPlayer(viewer.getUUID()) == viewer
                && level.getChunkSource().chunkMap.getPlayersWatching(entity).stream().anyMatch(player -> player == viewer);
    }

    public static boolean isVisible(Entity entity, ServerPlayer viewer) {
        NPC npc = NPCRegistries.lookup(entity);
        return !entity.isRemoved() && (npc == null || isVisible(npc, viewer));
    }

    public static boolean isVisible(NPC npc, ServerPlayer viewer) {
        Set<NPC> visited = new HashSet<>();
        for (NPC current = npc; current != null;) {
            if (!visited.add(current) || current.isHiddenFrom(viewer) || !current.isSpawned()) return false;
            ClickRedirectTrait redirect = current.getTraitNullable(ClickRedirectTrait.class);
            current = redirect == null ? null : redirect.getRedirectToNPC();
        }
        return true;
    }

    /** Called in the world tracking loop, including for stationary NPCs outside simulation distance. */
    public static void refresh(Entity entity) {
        if (NPCRegistries.lookup(entity) != null && entity.level() instanceof ServerLevel level)
            ((WorldTrackers) level.getChunkSource().chunkMap).citizens$refreshVisibility(entity);
    }

    /** Recreate existing viewers' client entities through their owning tracker, preserving the server entity. */
    public static void refreshPairing(Entity entity) {
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null || npc.getEntity() != entity || entity.isRemoved()) return;
        if (PacketNPC.isPacketEntity(entity)) {
            npc.getTraitNullable(PacketNPC.class).refreshPairing();
        } else if (entity.level() instanceof ServerLevel level) {
            ((WorldTrackers) level.getChunkSource().chunkMap).citizens$refreshPairing(entity);
        }
    }

    /** Snapshot of current viewers for profile/list updates outside the entity's normal broadcast path. */
    public static List<ServerPlayer> viewers(Entity entity) {
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null || npc.getEntity() != entity || entity.isRemoved()) return List.of();
        if (PacketNPC.isPacketEntity(entity)) {
            return npc.getTraitNullable(PacketNPC.class).getPacketTracker().getLinked().stream()
                    .filter(viewer -> isTracked(entity, viewer)).toList();
        }
        return ((ServerLevel) entity.level()).getChunkSource().chunkMap.getPlayersWatching(entity).stream()
                .filter(viewer -> isTracked(entity, viewer)).toList();
    }

    public interface WorldTrackers {
        void citizens$refreshVisibility(Entity entity);
        void citizens$refreshPairing(Entity entity);
    }

    /** Trait events concern the tracked target, not the player who receives it. */
    public static final class TrackingNPC implements TraitEventHandler.NPCEventExtractor {
        @Override public NPC apply(Event event) {
            return NPCRegistries.lookup(((PlayerEvent.StartTracking) event).getTarget());
        }
    }

    public interface TrackedEntity {
        void citizens$updateViewers(List<ServerPlayer> viewers);
        void citizens$refreshPairing();
    }
}
