package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Represents an event where an NPC was clicked by a player.
 */
public abstract class NPCClickEvent extends NPCEvent implements ICancellableEvent {
    private final ServerPlayer clicker;

    protected NPCClickEvent(NPC npc, ServerPlayer clicker) {
        super(npc);
        this.clicker = clicker;
    }

    /**
     * Gets the player that clicked the NPC.
     *
     * @return Player that clicked the NPC
     */
    public ServerPlayer getClicker() {
        return clicker;
    }
}
