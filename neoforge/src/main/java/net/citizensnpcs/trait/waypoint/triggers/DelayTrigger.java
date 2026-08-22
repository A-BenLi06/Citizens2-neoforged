package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Location;

/**
 * Pauses at the waypoint. The waiting is done by {@code Waypoint.onReach}, which pauses the provider and resumes the
 * remaining triggers afterwards, so this carries the duration and nothing else.
 */
public class DelayTrigger implements WaypointTrigger {
    @Persist
    private int delay = 0;

    public DelayTrigger() {
    }

    public DelayTrigger(int delay) {
        this.delay = delay;
    }

    @Override
    public String description() {
        return String.format("[[Delay]] for [[%d]] ticks", delay);
    }

    public int getDelay() {
        return delay;
    }

    @Override
    public void onWaypointReached(NPC npc, Location waypoint) {
    }
}
