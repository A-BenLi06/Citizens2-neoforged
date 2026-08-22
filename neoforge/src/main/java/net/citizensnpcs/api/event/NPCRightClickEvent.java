package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.server.level.ServerPlayer;

/**
 * Called when an NPC is right-clicked by a player.
 */
public class NPCRightClickEvent extends NPCClickEvent {
    private boolean toCancel;

    public NPCRightClickEvent(NPC npc, ServerPlayer click) {
        super(npc, click);
    }

    /**
     * Whether a listener handled the click and the underlying interaction should be suppressed, but only after every
     * other listener has had a look. Cancelling outright would stop that dispatch; this defers it to the caller.
     */
    public boolean isDelayedCancellation() {
        return toCancel;
    }

    public void setDelayedCancellation(boolean toCancel) {
        this.toCancel = toCancel;
    }
}
