package net.citizensnpcs.api.persistence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.util.MemoryDataKey;

/**
 * Covers the two ways {@link PersistenceLoader} mishandled data written by the Citizens <em>plugin</em> rather than by
 * this port. Both were found by loading a real 200-NPC {@code saves.yml} from a 1.20.1 Arclight server.
 * <p>
 * Bukkit's configuration API typed values on the way out and omitted nothing it had defaults for, so upstream never meets
 * either case; SnakeYAML hands back exactly what the file says.
 */
public class PersistenceLoaderLegacyValuesTest {
    public static class Sample {
        @Persist
        double range = 5;
        @Persist
        int delay = 60;
        @Persist
        boolean enabled;
        @Persist("pitchrange")
        float[] pitchRange = { -20, 20 };
        @Persist
        int[] counts = { 1, 2, 3 };
    }

    @Test
    public void quotedNumbersLoadIntoNumericFields() {
        MemoryDataKey key = new MemoryDataKey();
        // exactly how the plugin writes them
        key.setString("range", "7.5");
        key.setString("delay", "120");
        key.setString("enabled", "true");

        Sample loaded = PersistenceLoader.load(new Sample(), key);

        assertEquals(7.5, loaded.range, 1.0E-9);
        assertEquals(120, loaded.delay);
        assertEquals(true, loaded.enabled);
    }

    @Test
    public void anAbsentArrayKeyLeavesTheFieldInitialiserAlone() {
        // the plugin never wrote LookClose's look ranges, and rebuilding them from nothing produced a zero-length array,
        // which made randomLook throw ArrayIndexOutOfBounds every tick
        Sample loaded = PersistenceLoader.load(new Sample(), new MemoryDataKey());

        assertArrayEquals(new float[] { -20, 20 }, loaded.pitchRange, 0F);
        assertArrayEquals(new int[] { 1, 2, 3 }, loaded.counts);
    }

    @Test
    public void aPresentArrayKeyStillWins() {
        MemoryDataKey key = new MemoryDataKey();
        key.setDouble("pitchrange.0", -5);
        key.setDouble("pitchrange.1", 5);

        Sample loaded = PersistenceLoader.load(new Sample(), key);

        assertArrayEquals(new float[] { -5, 5 }, loaded.pitchRange, 0F);
    }

    @Test
    public void garbageInANumericFieldLeavesTheDefault() {
        MemoryDataKey key = new MemoryDataKey();
        key.setString("range", "not a number");

        Sample loaded = PersistenceLoader.load(new Sample(), key);

        assertEquals(5, loaded.range, 1.0E-9);
    }
}
