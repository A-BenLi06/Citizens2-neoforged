package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called just before an NPC's command list is dispatched. Cancelling stops every command in the list from running.
 */
public class NPCCommandDispatchEvent extends NPCEvent implements ICancellableEvent {
    private final ServerPlayer player;

    public NPCCommandDispatchEvent(NPC npc, ServerPlayer player) {
        super(npc);
        this.player = player;
    }

    /**
     * @return the player the commands will be dispatched on
     */
    public ServerPlayer getPlayer() {
        return player;
    }
}
