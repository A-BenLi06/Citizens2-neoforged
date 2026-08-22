package net.citizensnpcs.trait;

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
    private int t;

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

    @Override
    public void run() {
        if (!npc.isSpawned() || location == null || location.getWorld() == null
                || npc.getNavigator().isNavigating()) {
            t = 0;
            return;
        }
        Location current = npc.getStoredLocation();
        if (current == null || current.getWorld() != location.getWorld()) {
            // a different dimension is always "away from home", and only a teleport can fix it
            if (current != null && (delay == -1 || ++t > delay)) {
                npc.teleport(location.clone(), TeleportCause.PLUGIN);
            }
            return;
        }
        if (current.distanceSquared(location) < 0.01) {
            t = 0;
            return;
        }
        t++;
        if (t <= delay && delay != -1)
            return;
        if (distance != -1 && current.distanceSquared(location) < distance * distance)
            return;
        if (strategy == ReturnStrategy.TELEPORT) {
            npc.teleport(location.clone(), TeleportCause.PLUGIN);
        } else {
            npc.getNavigator().setTarget(location.clone());
            npc.getNavigator().getLocalParameters().distanceMargin(0.9).pathDistanceMargin(0)
                    .destinationTeleportMargin(1);
        }
    }

    public void setDelayTicks(int delay) {
        this.delay = delay;
    }

    public void setDistanceBlocks(double distance) {
        this.distance = distance;
    }

    public void setHomeLocation(Location location) {
        this.location = location == null ? null : location.clone();
    }

    public void setReturnStrategy(ReturnStrategy strategy) {
        this.strategy = strategy;
    }

    public enum ReturnStrategy {
        PATHFIND,
        TELEPORT
    }
}
