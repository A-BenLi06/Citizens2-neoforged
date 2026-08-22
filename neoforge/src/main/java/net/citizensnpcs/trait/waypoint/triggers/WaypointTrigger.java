package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.util.Location;

/** Something that happens when an NPC reaches a waypoint. */
public interface WaypointTrigger {
    /** A one-line description shown in the waypoint editor, in Citizens' {@code [[highlight]]} syntax. */
    String description();

    void onWaypointReached(NPC npc, Location waypoint);
}
