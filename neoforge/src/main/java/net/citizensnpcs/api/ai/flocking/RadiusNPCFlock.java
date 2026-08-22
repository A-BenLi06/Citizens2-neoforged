package net.citizensnpcs.api.ai.flocking;

import java.util.ArrayList;
import java.util.Collection;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;

/**
 * A flock made of the navigating NPCs within a block radius.
 * <p>
 * Upstream reads this from {@code LocationLookup}, a cache it keeps because the Bukkit query behind it is slow. The
 * registry is walked directly here for the same reason the rest of the port dropped that cache: the query is cheap.
 * The result is still cached for a few ticks, which is what keeps the cost down when many NPCs flock at once.
 */
public class RadiusNPCFlock implements NPCFlock {
    private Collection<NPC> cached;
    private int cacheTicks = 0;
    private final int maxCacheTicks;
    private final double radius;

    public RadiusNPCFlock(double radius) {
        this(radius, 30);
    }

    /**
     * @param radius
     *            how far to look for nearby NPCs, in blocks
     * @param maxCacheTicks
     *            how long a flock may be reused before it is recomputed; 0 or less disables caching
     */
    public RadiusNPCFlock(double radius, int maxCacheTicks) {
        this.radius = radius;
        this.maxCacheTicks = maxCacheTicks;
    }

    @Override
    public Collection<NPC> getNearby(NPC npc) {
        if (cached != null && cacheTicks++ < maxCacheTicks)
            return cached;
        cached = null;
        cacheTicks = 0;
        Collection<NPC> ret = new ArrayList<>();
        if (!npc.isSpawned())
            return ret;
        double radiusSquared = radius * radius;
        for (NPC other : CitizensAPI.getNPCRegistry()) {
            if (other == npc || !other.isSpawned() || !other.getNavigator().isNavigating())
                continue;
            if (other.getEntity().level() != npc.getEntity().level())
                continue;
            if (other.getEntity().distanceToSqr(npc.getEntity()) <= radiusSquared) {
                ret.add(other);
            }
        }
        return maxCacheTicks <= 0 ? ret : (cached = ret);
    }
}
