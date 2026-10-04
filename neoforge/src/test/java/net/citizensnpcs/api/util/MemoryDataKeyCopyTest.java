package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

public class MemoryDataKeyCopyTest {
    @Test @SuppressWarnings("unchecked")
    void snapshotsDetachNestedListsMapsAndArraysWithoutChangingLiteralKeys() {
        List<Object> sequence = new ArrayList<>();
        Map<String,Object> nested = new LinkedHashMap<>();
        byte[] bytes = new byte[] { 1, 2 };
        nested.put("literal.key", "original"); nested.put("bytes", bytes); sequence.add(nested);
        MemoryDataKey source = new MemoryDataKey(); source.setRaw("payload", sequence);
        MemoryDataKey snapshot = source.copy();
        nested.put("literal.key", "changed"); bytes[0] = 9; sequence.add("later");
        List<Object> copied = (List<Object>)snapshot.getRaw("payload");
        Map<String,Object> copiedMap = (Map<String,Object>)copied.get(0);
        assertEquals(1, copied.size()); assertEquals("original", copiedMap.get("literal.key"));
        assertArrayEquals(new byte[] { 1, 2 }, (byte[])copiedMap.get("bytes"));
        copied.clear(); assertEquals(2, sequence.size());
    }

    @Test
    void rootPreservingCopiesKeepLiteralPathsAndAbsoluteReferences() {
        MemoryDataKey source = new MemoryDataKey();
        source.setMap("records", new LinkedHashMap<>(Map.of("literal.key", new LinkedHashMap<>(Map.of("value", "original")))));
        source.setString("global.provider.value", "shared");
        DataKey record = source.getRelative("records").getSubKeys().iterator().next();
        DataKey snapshot = record.copyWithRoot();
        assertEquals(record.name(), snapshot.name());
        assertEquals(record.getPath(), snapshot.getPath());
        assertEquals("original", snapshot.getString("value"));
        assertEquals("shared", snapshot.getFromRoot("global.provider").getString("value"));
        snapshot.setString("value", "detached");
        snapshot.getFromRoot("global.provider").setString("value", "detached shared");
        assertEquals("original", record.getString("value"));
        assertEquals("shared", source.getString("global.provider.value"));
        source.setRaw("", snapshot.getFromRoot("").getValuesDeep());
        assertEquals("detached", record.getString("value"));
        assertEquals("detached shared", source.getString("global.provider.value"));
        assertNull(source.getRaw("records.literal"));
    }
}
