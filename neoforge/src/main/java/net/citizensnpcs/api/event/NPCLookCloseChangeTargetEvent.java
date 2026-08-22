package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.entity.player.Player;

/**
 * Fired when a look-close NPC changes which player it is looking at. Listeners may redirect it with
 * {@link #setNewTarget}.
 */
public class NPCLookCloseChangeTargetEvent extends NPCEvent {
    private Player next;
    private final Player old;

    public NPCLookCloseChangeTargetEvent(NPC npc, Player old, Player next) {
        super(npc);
        this.old = old;
        this.next = next;
    }

    public Player getNewTarget() {
        return next;
    }

    public Player getPreviousTarget() {
        return old;
    }

    public void setNewTarget(Player target) {
        this.next = target;
    }
}
