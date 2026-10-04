package net.citizensnpcs.api.persistence;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.api.util.MemoryDataKey;

class StrictPersistenceTest {
    @Test
    void nestedFailurePropagatesWithoutChangingLegacyModeAfterward() {
        MemoryDataKey key = new MemoryDataKey();
        key.setRaw("children", Map.of("0", Map.of("other", "present")));
        assertThrows(IllegalStateException.class, () -> PersistenceLoader.loadStrict(new Holder(), key));
        Holder legacy = PersistenceLoader.load(new Holder(), key);
        assertNotNull(legacy);
        assertTrue(legacy.children.isEmpty());
        key.setRaw("children", Map.of("0", Map.of("value", "valid")));
        Holder valid = PersistenceLoader.loadStrict(new Holder(), key);
        assertEquals("valid", valid.children.getFirst().value);
    }
    static final class Holder {
        @Persist(reify = true) List<Entry> children = new ArrayList<>();
    }
    static final class Entry {
        @Persist(required = true) String value;
    }
}
