package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * Called when an NPC teleports.
 */
public class NPCTeleportEvent extends NPCEvent implements ICancellableEvent {
    private final Location to;

    public NPCTeleportEvent(NPC npc, Location to) {
        super(npc);
        this.to = to;
    }

    public Location getFrom() {
        return npc.getStoredLocation();
    }

    public Location getTo() {
        return to;
    }
}
