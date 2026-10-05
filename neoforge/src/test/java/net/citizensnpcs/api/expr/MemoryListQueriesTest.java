package net.citizensnpcs.api.expr;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class MemoryListQueriesTest {
    @Test void queriesDoNotCreateMissingKeysOrReplaceScalarValues() {
        Memory memory = new Memory();
        memory.set("scalar", "keep");
        for (String key : List.of("absent", "scalar")) {
            assertEquals(0, memory.listSize(key));
            assertFalse(memory.listContains(key, "value"));
            assertNull(memory.listGet(key, 0));
        }
        assertFalse(memory.has("absent"));
        assertEquals("keep", memory.getString("scalar"));
        assertEquals(1, memory.size());
    }

    @Test void ineffectiveRemovalsDoNotChangeNonListState() {
        Memory memory = new Memory();
        memory.set("scalar", 7);
        for (String key : List.of("absent", "scalar")) {
            assertFalse(memory.listRemove(key, 7));
            assertNull(memory.listRemoveAt(key, 0));
        }
        assertFalse(memory.has("absent"));
        assertEquals(7D, memory.getNumber("scalar"));
        memory.set("list", List.of("first", "second"));
        assertEquals("first", memory.listGet("list", 0));
        assertTrue(memory.listRemove("list", "first"));
        assertEquals("second", memory.listRemoveAt("list", 0));
        assertEquals(0, memory.listSize("list"));
    }

    @Test void invalidIndexWriteFailsBeforeCreatingOrReplacingAValue() {
        Memory memory = new Memory();
        memory.set("scalar", "keep");
        assertThrows(IndexOutOfBoundsException.class, () -> memory.listSet("absent", -1, "new"));
        assertThrows(IndexOutOfBoundsException.class, () -> memory.listSet("scalar", -1, "new"));
        assertFalse(memory.has("absent"));
        assertEquals("keep", memory.getString("scalar"));
        memory.listSet("newList", 1, "value");
        assertNull(memory.listGet("newList", 0));
        assertEquals("value", memory.listGet("newList", 1));
    }
}
