package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;

class MemoryKeyIdentityTest {
    @Test void nativeNamesAndDottedNamesRemainBlackboardKeysOnDisk(@TempDir Path directory) {
        Memory memory = new Memory();
        String nativeName = NPC.Metadata.SHOULD_SAVE.getKey();
        memory.set(nativeName, "blackboard value");
        memory.set("literal.key", 42);
        memory.set("literal.list", List.of("one", "two"));
        var storage = new YamlStorage(directory.resolve("memory.yml").toFile());
        assertTrue(storage.load());
        memory.saveTo(storage.getKey("")); storage.save();
        assertTrue(storage.load());
        Memory restored = new Memory(); restored.loadFrom(storage.getKey(""));
        assertEquals("blackboard value", restored.getString(nativeName));
        assertEquals(42D, restored.getNumber("literal.key"));
        assertEquals(List.of("one", "two"), restored.getList("literal.list"));
        assertFalse(restored.has("literal"));
        assertEquals(3, restored.size());
    }

    @Test void injectedNativeStoreAlsoUsesStringKeysForBlackboardLoading() {
        var store = new SimpleMetadataStore();
        Memory memory = new Memory(store);
        var saved = new MemoryDataKey();
        saved.setRaw("", java.util.Map.of(NPC.Metadata.SHOULD_SAVE.getKey(), "memory"));
        memory.loadFrom(saved);
        assertEquals("memory", store.get(NPC.Metadata.SHOULD_SAVE.getKey()));
        assertFalse(store.has(NPC.Metadata.SHOULD_SAVE));
    }

    @Test void ordinaryMetadataLoadingStillResolvesNativeKeys() {
        var store = new SimpleMetadataStore();
        var saved = new MemoryDataKey();
        saved.setRaw("", java.util.Map.of(NPC.Metadata.SHOULD_SAVE.getKey(), false));
        store.loadFrom(saved);
        assertEquals(Boolean.FALSE, store.get(NPC.Metadata.SHOULD_SAVE));
        assertFalse(store.has(NPC.Metadata.SHOULD_SAVE.getKey()));
    }
}
