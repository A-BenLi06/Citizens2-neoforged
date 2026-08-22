package net.citizensnpcs.trait.waypoint;

import java.util.ListIterator;

import net.citizensnpcs.api.event.CitizensEvent;

/**
 * Fired when a linear route runs out of waypoints. A listener can hand back a fresh iterator to keep the NPC walking,
 * which is how a one-shot route is turned into something else.
 */
public class LinearWaypointsCompleteEvent extends CitizensEvent {
    private ListIterator<Waypoint> next;
    private final WaypointProvider provider;

    public LinearWaypointsCompleteEvent(WaypointProvider provider, ListIterator<Waypoint> next) {
        this.next = next;
        this.provider = provider;
    }

    public ListIterator<Waypoint> getNextWaypoints() {
        return next;
    }

    public WaypointProvider getWaypointProvider() {
        return provider;
    }

    public void setNextWaypoints(ListIterator<Waypoint> waypoints) {
        next = waypoints;
    }
}
