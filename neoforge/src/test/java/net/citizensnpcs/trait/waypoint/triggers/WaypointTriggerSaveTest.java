package net.citizensnpcs.trait.waypoint.triggers;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Location;
import net.citizensnpcs.api.util.MemoryDataKey;

class WaypointTriggerSaveTest {
    public static class UnknownTrigger implements WaypointTrigger {
        @Persist public String value = "new value";
        public String description() { return "unknown"; }
        public void onWaypointReached(NPC npc, Location at) { }
    }
    public static class BrokenTrigger extends UnknownTrigger implements Persistable {
        public void load(DataKey root) { }
        public void save(DataKey root) {
            root.setString("partial", "should not publish");
            throw new IllegalStateException("provider save failed");
        }
    }

    @Test void unregisteredTypesCannotOverwriteAnExistingRecord() {
        var registry = new WaypointTriggerRegistry(); var root = new MemoryDataKey();
        registry.save(new DelayTrigger(13), root);
        var before = root.copy().getValuesDeep();
        assertThrows(IllegalArgumentException.class, () -> registry.save(new UnknownTrigger(), root));
        assertEquals(before, root.getValuesDeep());
        assertEquals(13, ((DelayTrigger) registry.create(root)).getDelay());
    }

    @Test void providerSaveFailurePreservesTheCompletePreviousRecord() {
        WaypointTriggerRegistry.addTrigger("save_failure_probe", BrokenTrigger.class, () -> null);
        var registry = new WaypointTriggerRegistry(); var root = new MemoryDataKey();
        registry.save(new SpeedTrigger(2), root);
        var before = root.copy().getValuesDeep();
        assertThrows(IllegalStateException.class, () -> registry.save(new BrokenTrigger(), root));
        assertEquals(before, root.getValuesDeep());
        assertEquals(2F, ((SpeedTrigger) registry.create(root)).getSpeed());
    }

    @Test void replacingATypeRemovesStaleFieldsWithoutChangingSiblings() {
        var registry = new WaypointTriggerRegistry(); var root = new MemoryDataKey();
        root.setString("sibling", "preserved");
        DataKey target = root.getRelative("trigger");
        registry.save(new DelayTrigger(13), target);
        registry.save(new SpeedTrigger(2), target);
        assertFalse(target.keyExists("delay"));
        assertEquals("speed", target.getString("type"));
        assertEquals(2F, ((SpeedTrigger) registry.create(target)).getSpeed());
        assertEquals("preserved", root.getString("sibling"));
        registry.save(new DelayTrigger(0), target);
        assertEquals(0, ((DelayTrigger) registry.create(target)).getDelay());
        assertFalse(target.keyExists("speed"));
    }
}
