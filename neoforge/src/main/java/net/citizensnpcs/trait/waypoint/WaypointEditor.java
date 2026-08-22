package net.citizensnpcs.trait.waypoint;

import net.citizensnpcs.editor.Editor;

/** An {@link Editor} for a route. */
public abstract class WaypointEditor extends Editor {
    /** @return the waypoint currently being edited, or null when none is selected */
    public Waypoint getCurrentWaypoint() {
        return null;
    }
}
