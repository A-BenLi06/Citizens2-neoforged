package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called the first time a player starts tracking an NPC's entity, i.e. when the NPC enters that player's view distance
 * and the server begins sending them entity packets for it.
 * <p>
 * Upstream raises this from {@code CitizensEntityTracker}, a replacement for vanilla's {@code ChunkMap.TrackedEntity}
 * installed by {@code NMS.replaceTracker()}. NeoForge fires {@code PlayerEvent.StartTracking} for the same moment, so
 * this port needs neither the tracker replacement nor the Mixin it would have required.
 */
public class NPCSeenByPlayerEvent extends NPCEvent implements ICancellableEvent {
    private final ServerPlayer player;

    public NPCSeenByPlayerEvent(NPC npc, ServerPlayer player) {
        super(npc);
        this.player = player;
    }

    public ServerPlayer getPlayer() {
        return player;
    }
}
