package net.citizensnpcs.trait.waypoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.trait.waypoint.triggers.DelayTrigger;
import net.citizensnpcs.trait.waypoint.triggers.WaypointTrigger;
import net.citizensnpcs.trait.waypoint.triggers.WaypointTriggerRegistry;
import net.citizensnpcs.util.Messages;
import net.minecraft.commands.CommandSourceStack;

/** A place on a route, plus whatever should happen when the NPC gets there. */
public class Waypoint {
    @Persist(required = true)
    private Location location;
    @Persist
    private List<WaypointTrigger> triggers;

    /** For persistence — avoid using otherwise. */
    public Waypoint() {
    }

    public Waypoint(Location at) {
        location = at.clone();
    }

    public void addTrigger(WaypointTrigger trigger) {
        if (triggers == null) {
            triggers = new ArrayList<>();
        }
        triggers.add(trigger);
    }

    /** Lists the triggers with a clickable remove link beside each, which the trigger editor reads back. */
    public void describeTriggers(CommandSourceStack sender) {
        if (triggers == null)
            return;
        StringBuilder base = new StringBuilder(" ");
        for (int i = 0; i < triggers.size(); i++) {
            base.append("\n    - ").append(triggers.get(i).description())
                    .append(" [<hover:show_text:Remove trigger><click:run_command:/npc path remove_trigger ").append(i)
                    .append("><u><red>-</click></hover>]");
        }
        Messaging.sendTr(sender, Messages.WAYPOINT_TRIGGER_LIST, base.toString());
    }

    public double distance(Waypoint dest) {
        return location.distance(dest.location);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        Waypoint other = (Waypoint) obj;
        return Objects.equals(location, other.location) && Objects.equals(triggers, other.triggers);
    }

    public Location getLocation() {
        return location.clone();
    }

    public List<WaypointTrigger> getTriggers() {
        return triggers == null ? Collections.emptyList() : triggers;
    }

    @Override
    public int hashCode() {
        int result = 31 + (location == null ? 0 : location.hashCode());
        return 31 * result + (triggers == null ? 0 : triggers.hashCode());
    }

    public void onReach(NPC npc) {
        if (triggers == null)
            return;
        runTriggers(npc, 0);
    }

    /**
     * Runs the triggers in order, stopping at a {@link DelayTrigger} to pause the route and pick up where it left off
     * once the delay is up.
     */
    private void runTriggers(NPC npc, int start) {
        WaypointTrigger[] snapshot = triggers.toArray(new WaypointTrigger[0]);
        for (int i = start; i < snapshot.length; i++) {
            WaypointTrigger trigger = snapshot[i];
            trigger.onWaypointReached(npc, location.clone());
            if (!(trigger instanceof DelayTrigger delayTrigger))
                continue;
            int delay = delayTrigger.getDelay();
            if (delay <= 0)
                continue;
            WaypointProvider provider = npc.getOrAddTrait(Waypoints.class).getCurrentProvider();
            provider.setPaused(true);
            int resumeAt = i + 1;
            CitizensAPI.getScheduler().runTaskLater(() -> {
                provider.setPaused(false);
                runTriggers(npc, resumeAt);
            }, delay);
            break;
        }
    }

    @Override
    public String toString() {
        return "Waypoint [" + location + (triggers == null ? "]" : ", " + triggers.size() + " triggers]");
    }

    static {
        PersistenceLoader.registerPersistDelegate(WaypointTrigger.class, WaypointTriggerRegistry.class);
    }
}
