package net.citizensnpcs.trait.waypoint.triggers;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.util.Util;

/** Runs console commands on reaching the waypoint. */
public class CommandTrigger implements WaypointTrigger {
    @Persist
    private List<String> commands = new ArrayList<>();

    public CommandTrigger() {
    }

    public CommandTrigger(Collection<String> commands) {
        this.commands = new ArrayList<>(commands);
    }

    @Override
    public String description() {
        return String.format("[[Command]] running %s", String.join(", ", commands));
    }

    @Override
    public void onWaypointReached(NPC npc, Location waypoint) {
        for (String command : commands) {
            Util.runCommand(npc, null, command, false, false);
        }
    }
}
