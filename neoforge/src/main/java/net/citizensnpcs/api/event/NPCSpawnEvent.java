package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.neoforged.bus.api.ICancellableEvent;

public class NPCSpawnEvent extends NPCEvent implements ICancellableEvent {
    private final Location location;
    private final SpawnReason reason;

    public NPCSpawnEvent(NPC npc, Location location, SpawnReason reason) {
        super(npc);
        this.location = location;
        this.reason = reason;
    }

    public Location getLocation() {
        return location.clone();
    }

    public SpawnReason getReason() {
        return reason;
    }
}
