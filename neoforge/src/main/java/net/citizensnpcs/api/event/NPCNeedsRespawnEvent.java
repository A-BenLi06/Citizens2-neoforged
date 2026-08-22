package net.citizensnpcs.api.event;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.util.ChunkCoord;

/**
 * Fired when an NPC could not be spawned because its chunk was not available, so that the mod can retry once the chunk
 * loads.
 * <p>
 * Upstream declares this in the implementation rather than the API; it lives here because both the API's spawn path and
 * the mod's chunk listener need it.
 */
public class NPCNeedsRespawnEvent extends NPCEvent {
    private final ChunkCoord coord;
    private final Location location;

    public NPCNeedsRespawnEvent(NPC npc, ChunkCoord coord) {
        super(npc);
        this.coord = coord;
        this.location = null;
    }

    public NPCNeedsRespawnEvent(NPC npc, Location location) {
        super(npc);
        this.location = location;
        this.coord = new ChunkCoord(location);
    }

    public ChunkCoord getChunkCoord() {
        return coord;
    }

    /** @return the location to respawn at, or null if only the chunk is known */
    public Location getLocation() {
        return location == null ? null : location.clone();
    }
}
