package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.PersistenceLoader;

/**
 * Round-trip coverage for the storage layer: {@link YamlStorage} to disk and back, and {@link PersistenceLoader}'s
 * reflective field handling on top of it.
 * <p>
 * Deliberately avoids anything that needs a live server (Location, registry-keyed values, Components) — those are
 * covered once the NPC layer lands in P2/P4.
 */
public class YamlStorageRoundTripTest {
    public static class Sample {
        @Persist
        private boolean enabled;
        @Persist
        private double health;
        @Persist("renamed-key")
        private String label;
        @Persist
        private List<String> lines = new ArrayList<>();
        @Persist
        private Map<String, Integer> counts = new HashMap<>();
        @Persist
        private Mode mode = Mode.FIRST;
        @Persist
        private UUID id;
        @Persist
        private int[] numbers;
        private transient String notPersisted = "ignored";
    }

    public enum Mode {
        FIRST,
        SECOND
    }

    @Test
    public void deepKeysAndRemoval(@TempDir Path dir) {
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "nested.yml"));
        DataKey root = storage.getKey("");
        root.setString("a.b.c", "deep");
        root.setInt("a.b.d", 7);

        assertEquals("deep", root.getString("a.b.c"));
        assertEquals(7, root.getInt("a.b.d"));
        assertTrue(root.getRelative("a.b").keyExists("c"));

        root.removeKey("a.b.c");
        assertFalse(root.getRelative("a.b").keyExists("c"));
        assertEquals(7, root.getInt("a.b.d"), "sibling must survive removal");
    }

    @Test
    public void keysWithDotsInNameSurvive(@TempDir Path dir) {
        // MemoryDataKey exists precisely so that a literal dot in a key is not read as a path separator
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "dots.yml"));
        DataKey root = storage.getKey("traits");
        root.getRelative("some.trait").setString("value", "kept");
        storage.save();

        YamlStorage reloaded = new YamlStorage(new File(dir.toFile(), "dots.yml"));
        assertTrue(reloaded.load());
        assertEquals("kept", reloaded.getKey("traits").getRelative("some.trait").getString("value"));
    }

    @Test
    public void persistenceLoaderRoundTrip(@TempDir Path dir) {
        Sample original = new Sample();
        original.enabled = true;
        original.health = 17.5;
        original.label = "hello";
        original.lines = new ArrayList<>(List.of("one", "two", "three"));
        original.counts = new HashMap<>(Map.of("a", 1, "b", 2));
        original.mode = Mode.SECOND;
        original.id = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
        original.numbers = new int[] { 4, 5, 6 };

        File file = new File(dir.toFile(), "saves.yml");
        YamlStorage storage = new YamlStorage(file, "test");
        PersistenceLoader.save(original, storage.getKey("npc.1"));
        storage.save();

        YamlStorage reloaded = new YamlStorage(file);
        assertTrue(reloaded.load(), "storage should reload from disk");
        Sample loaded = PersistenceLoader.load(Sample.class, reloaded.getKey("npc.1"));

        assertNotNull(loaded);
        assertTrue(loaded.enabled);
        assertEquals(17.5, loaded.health);
        assertEquals("hello", loaded.label);
        assertEquals(List.of("one", "two", "three"), loaded.lines);
        assertEquals(Map.of("a", 1, "b", 2), loaded.counts);
        assertEquals(Mode.SECOND, loaded.mode);
        assertEquals(original.id, loaded.id);
        assertEquals(3, loaded.numbers.length);
        assertEquals(6, loaded.numbers[2]);
        assertEquals("ignored", loaded.notPersisted, "non-annotated fields keep their constructed value");
    }

    @Test
    public void renamedKeyIsUsedOnDisk(@TempDir Path dir) {
        Sample original = new Sample();
        original.label = "value";

        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "renamed.yml"));
        PersistenceLoader.save(original, storage.getKey("root"));

        DataKey root = storage.getKey("root");
        assertEquals("value", root.getString("renamed-key"));
        assertNull(root.getRaw("label"), "@Persist(\"renamed-key\") must override the field name");
    }

    /**
     * A header spanning several lines has to be commented on every one of them.
     * <p>
     * Only the first line used to get a '#', which left the rest of the header as bare document text: the file was no
     * longer valid YAML, and it failed by silently parsing as nothing rather than by raising anything.
     */
    @Test
    public void aMultiLineHeaderIsCommentedOnEveryLine(@TempDir Path dir) throws Exception {
        File file = new File(dir.toFile(), "headered.yml");
        YamlStorage storage = new YamlStorage(file, "line one\n\nline three");
        storage.getKey("").setRaw("a.b", "c");
        storage.save();

        List<String> lines = Files.readAllLines(file.toPath());
        assertTrue(lines.contains("# line one"), "first header line");
        assertTrue(lines.contains("#"), "a blank header line stays a comment");
        assertTrue(lines.contains("# line three"), "a later header line is commented too");

        YamlStorage reread = new YamlStorage(file, null);
        assertTrue(reread.load(), "the file still parses");
        assertEquals("c", reread.getKey("a").getString("b"), "and the data survived the header");
    }
}
