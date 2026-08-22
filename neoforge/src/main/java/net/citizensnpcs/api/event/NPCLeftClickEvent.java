package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;

/**
 * Called when an NPC is left-clicked by a player.
 */
public class NPCLeftClickEvent extends NPCClickEvent {
    public NPCLeftClickEvent(NPC npc, ServerPlayer click) {
        super(npc, click);
    }
}
