package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called when an NPC despawns.
 */
public class NPCDespawnEvent extends NPCEvent implements ICancellableEvent {
    private final DespawnReason reason;

    public NPCDespawnEvent(NPC npc, DespawnReason reason) {
        super(npc);
        this.reason = reason;
    }

    public DespawnReason getReason() {
        return reason;
    }
}
