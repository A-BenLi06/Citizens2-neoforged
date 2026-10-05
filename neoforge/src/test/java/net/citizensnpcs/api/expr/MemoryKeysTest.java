package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.api.util.MemoryDataKey;

class MemoryKeysTest {
    private static Set<String> keys(Memory memory) {
        Set<String> keys = new HashSet<>();
        memory.getKeys().forEach(keys::add);
        return keys;
    }

    @Test void enumeratesPersistentAndListEntriesWithoutExposingALiveView() {
        Memory memory = new Memory();
        memory.set("counter", 3);
        memory.getList("items").add("first");
        Iterable<String> before = memory.getKeys();
        memory.set("later", true);
        Set<String> snapshot = new HashSet<>(); before.forEach(snapshot::add);
        assertEquals(Set.of("counter", "items"), snapshot);
        assertEquals(Set.of("counter", "items", "later"), keys(memory));
        memory.remove("counter");
        assertEquals(Set.of("items", "later"), keys(memory));
    }

    @Test void clearRemovesLoadedAndSessionStateFromTheBackingStore() {
        var store = new SimpleMetadataStore();
        Memory memory = new Memory(store);
        var data = new MemoryDataKey(); data.setString("loaded", "value");
        memory.loadFrom(data);
        memory.getList("session").add(1);
        store.set("external", "added directly");
        assertEquals(Set.of("loaded", "session", "external"), keys(memory));
        memory.clear();
        assertEquals(0, memory.size());
        assertEquals(0, store.size());
        assertTrue(keys(memory).isEmpty());
        memory.saveTo(data);
        assertFalse(data.hasSubKeys());
        Memory reloaded = new Memory(); reloaded.loadFrom(data);
        assertEquals(0, reloaded.size());
    }

    @Test void nativeKeyEnumerationAndClearIncludeTypedSessionOverrides() {
        var store = new SimpleMetadataStore();
        store.setPersistent(NPC.Metadata.SHOULD_SAVE, true);
        store.setTemporary(NPC.Metadata.SHOULD_SAVE, false);
        store.set("custom", 7);
        Memory memory = new Memory(store);
        assertEquals(Set.of(NPC.Metadata.SHOULD_SAVE.getKey(), "custom"), keys(memory));
        memory.clear();
        assertFalse(store.has(NPC.Metadata.SHOULD_SAVE));
        assertFalse(store.has("custom"));
        var saved = new MemoryDataKey(); store.saveTo(saved);
        assertFalse(saved.hasSubKeys());
    }

    @Test void savingRemovesDeletedKeysWithoutChangingSiblingSections() {
        var root = new MemoryDataKey();
        root.setString("other", "keep sibling");
        var section = root.getRelative("memory");
        Memory memory = new Memory();
        memory.set("keep", 1); memory.set("deleted", 2);
        memory.saveTo(section);
        memory.remove("deleted");
        memory.saveTo(section);
        assertFalse(section.keyExists("deleted"));
        assertEquals(1, section.getInt("keep"));
        assertEquals("keep sibling", root.getString("other"));
    }
}
