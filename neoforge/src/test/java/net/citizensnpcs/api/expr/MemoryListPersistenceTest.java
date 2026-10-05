package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.util.MemoryDataKey;

class MemoryListPersistenceTest {
    @Test void reloadingReplacesOldListsAndScalarsInsteadOfRetainingTransientEntries() {
        Memory memory = new Memory();
        memory.set("oldScalar", 1);
        memory.listAdd("oldList", "remove");
        memory.listAdd("changed", "old");
        var incoming = new MemoryDataKey();
        incoming.setString("changed", "new scalar");
        incoming.setRaw("newList", List.of(2));
        memory.loadFrom(incoming);
        assertFalse(memory.has("oldScalar"));
        assertFalse(memory.has("oldList"));
        assertEquals("new scalar", memory.getString("changed"));
        assertEquals(List.of(2), memory.getList("newList"));
        memory.loadFrom(new MemoryDataKey());
        assertEquals(0, memory.size());
    }

    @Test void failedLoadPreparationKeepsPreviousMemory() {
        Memory memory = new Memory();
        memory.set("scalar", 1);
        memory.listAdd("list", "keep");
        var unreadable = new MemoryDataKey() {
            @Override public MemoryDataKey copyWithRoot() { throw new IllegalStateException("snapshot failure"); }
        };
        assertThrows(IllegalStateException.class, () -> memory.loadFrom(unreadable));
        assertEquals(1D, memory.getNumber("scalar"));
        assertEquals(List.of("keep"), memory.getList("list"));
    }

    @Test void listOperationsAndEmptyListsSurviveSaveAndLoad(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) {
        Memory memory = new Memory();
        memory.set("values", Arrays.asList("first", 2, true, null));
        memory.listAdd("values", "last");
        memory.listClear("empty");
        var storage = new net.citizensnpcs.api.util.YamlStorage(directory.resolve("memory.yml").toFile());
        assertTrue(storage.load());
        memory.saveTo(storage.getKey("")); storage.save();
        assertTrue(storage.load());
        Memory restored = new Memory(); restored.loadFrom(storage.getKey(""));
        assertEquals(Arrays.asList("first", 2, true, null, "last"), restored.getList("values"));
        assertTrue(restored.has("empty"));
        assertEquals(List.of(), restored.getList("empty"));
    }

    @Test void listStateDoesNotAliasCallerInputOrPersistedSnapshots() {
        Memory memory = new Memory();
        var input = new ArrayList<Object>(List.of("initial"));
        memory.set("values", input);
        input.add("external");
        assertEquals(List.of("initial"), memory.getList("values"));
        memory.set("immutable", List.of(1));
        assertDoesNotThrow(() -> memory.listAdd("immutable", 2));
        var saved = new MemoryDataKey(); memory.saveTo(saved);
        Memory restored = new Memory(); restored.loadFrom(saved);
        restored.listAdd("values", "restored only");
        memory.listAdd("values", "original only");
        assertEquals(List.of("initial"), saved.getRaw("values"));
        assertEquals(List.of("initial", "restored only"), restored.getList("values"));
    }

    @Test void invalidListMutationCannotDestroyThePriorSave() {
        Memory memory = new Memory();
        memory.listAdd("values", 1);
        var saved = new MemoryDataKey(); memory.saveTo(saved);
        memory.getList("values").add(new Object());
        assertThrows(IllegalArgumentException.class, () -> memory.saveTo(saved));
        assertEquals(List.of(1), saved.getRaw("values"));
    }
}
