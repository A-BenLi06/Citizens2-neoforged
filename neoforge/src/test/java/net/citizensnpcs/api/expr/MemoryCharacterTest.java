package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.api.npc.SimpleMetadataStore;
import net.citizensnpcs.api.util.YamlStorage;

class MemoryCharacterTest {
    @Test void supportedCharacterValuesCanBeStoredAndRoundTripAsYamlText(@TempDir Path directory) {
        Memory memory = new Memory();
        assertDoesNotThrow(() -> memory.set("character", '界'));
        assertEquals(Character.valueOf('界'), memory.get("character"));
        var storage = new YamlStorage(directory.resolve("memory.yml").toFile());
        assertTrue(storage.load());
        memory.saveTo(storage.getKey("")); storage.save();
        assertTrue(storage.load());
        Memory restored = new Memory(); restored.loadFrom(storage.getKey(""));
        assertEquals("界", restored.getString("character"));
    }

    @Test void extendingPrimitiveSupportDoesNotAdmitArbitraryObjects() {
        var store = new SimpleMetadataStore();
        store.setPersistent("value", 'x');
        assertThrows(IllegalArgumentException.class, () -> store.setPersistent("value", new Object()));
        assertEquals(Character.valueOf('x'), store.get("value"));
    }
}
