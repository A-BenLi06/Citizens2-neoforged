package net.citizensnpcs.trait;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;

/**
 * Returns an NPC to a fixed spot when it drifts away from it — by teleporting, or by walking back.
 * <p>
 * A delay of -1 means "act immediately" and a distance of -1 means "any distance at all", which is upstream's convention
 * for "not configured".
 */
@TraitName("hometrait")
public class HomeTrait extends Trait {
    @Persist
    private int delay = -1;
    @Persist
    private double distance = -1;
    @Persist
    private Location location;
    @Persist
    private ReturnStrategy strategy = ReturnStrategy.TELEPORT;
    private long t;
    private NavigatorParameters ownedParameters;
    private boolean removed;
    private boolean returning;

    public HomeTrait() {
        super("hometrait");
    }

    public int getDelayTicks() {
        return delay;
    }

    public double getDistanceBlocks() {
        return distance;
    }

    public Location getHomeLocation() {
        return location == null ? null : location.clone();
    }

    public ReturnStrategy getReturnStrategy() {
        return strategy;
    }

    private boolean isCurrent() {
        return !removed && npc != null && npc.getTraitNullable(HomeTrait.class) == this;
    }

    @Override
    public void onDespawn() {
        t = 0;
        releaseRoute();
    }

    @Override
    public void onRemove() {
        removed = true;
        t = 0;
        releaseRoute();
    }

    @Override
    public void onSpawn() {
        if (isCurrent()) t = 0;
    }

    private void releaseRoute() {
        NavigatorParameters owned = ownedParameters;
        ownedParameters = null;
        if (npc != null && owned != null && npc.getNavigator().getLocalParameters() == owned) {
            npc.getNavigator().cancelNavigation();
        }
    }

    @Override
    public void run() {
        if (returning) return;
        if (!isCurrent() || !npc.isSpawned() || location == null || location.getWorld() == null
                || !finite(location) || !Double.isFinite(distance) || strategy == null
                || npc.getNavigator().getPathStrategy() != null
                || npc.getNavigator().getLocalParameters() != npc.getNavigator().getDefaultParameters()) {
            t = 0;
            return;
        }
        ownedParameters = null;
        Location current = npc.getStoredLocation();
        if (current == null) {
            t = 0;
            return;
        }
        boolean differentWorld = current.getWorld() != location.getWorld();
        double separation = differentWorld ? Double.POSITIVE_INFINITY : current.distanceSquared(location);
        if (separation < 0.01) {
            t = 0;
            return;
        }
        // Stop counting once eligible so even the largest stored int delay cannot wrap around.
        if (t <= delay) t++;
        if (t <= delay)
            return;
        if (Math.sqrt(separation) < distance)
            return;
        Location destination = location.clone();
        returning = true;
        try {
            if (strategy == ReturnStrategy.TELEPORT) {
                npc.teleport(destination, TeleportCause.PLUGIN);
            } else if (!differentWorld) {
                npc.getNavigator().setTarget(destination, params -> {
                    ownedParameters = params;
                    params.distanceMargin(0.9).pathDistanceMargin(0).destinationTeleportMargin(1);
                });
            }
        } finally {
            returning = false;
        }
    }

    public void setDelayTicks(int delay) {
        this.delay = delay;
    }

    public void setDistanceBlocks(double distance) {
        if (!Double.isFinite(distance)) throw new IllegalArgumentException("Distance must be finite");
        this.distance = distance;
    }

    public void setHomeLocation(Location location) {
        if (location != null && !finite(location)) throw new IllegalArgumentException("Home location must be finite");
        this.location = location == null ? null : location.clone();
        t = 0;
        releaseRoute();
    }

    public void setReturnStrategy(ReturnStrategy strategy) {
        this.strategy = java.util.Objects.requireNonNull(strategy, "strategy");
        releaseRoute();
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ())
                && Float.isFinite(location.getYaw()) && Float.isFinite(location.getPitch());
    }

    public enum ReturnStrategy {
        PATHFIND,
        TELEPORT
    }
}
