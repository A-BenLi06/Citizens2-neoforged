package net.citizensnpcs.trait.waypoint.triggers;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.TeleportCause;

/** Teleports the NPC somewhere on reaching the waypoint. */
public class TeleportTrigger implements WaypointTrigger {
    @Persist(required = true)
    private Location location;

    public TeleportTrigger() {
    }

    public TeleportTrigger(Location location) {
        this.location = location;
    }

    @Override
    public String description() {
        if (location == null)
            return "[[Teleport]] (unset)";
        return String.format("[[Teleport]] to [%s, %d, %d, %d]",
                location.getWorld() == null ? "?" : location.getWorld().dimension().location(), location.getBlockX(),
                location.getBlockY(), location.getBlockZ());
    }

    @Override
    public void onWaypointReached(NPC npc, Location waypoint) {
        if (location != null) {
            npc.teleport(location, TeleportCause.PLUGIN);
        }
    }
}
