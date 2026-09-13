package net.citizensnpcs.api.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.YamlStorage;

class PersistenceMapKeysTest {
    static class Values {
        @Persist Map<String, String> values = new LinkedHashMap<>();
        @Persist(reify = true) Map<String, Child> children = new LinkedHashMap<>();
    }
    static class Child {
        @Persist("$key") String name;
        @Persist String value;
        Child() { }
        Child(String value) { this.value = value; }
    }

    @Test void literalMapNamesDoNotCollideWithNestedPaths() {
        Values source = new Values();
        source.values.put("a", "first"); source.values.put("a.b", "second");
        source.values.put("provider:custom.id", "third");
        var key = new MemoryDataKey(); PersistenceLoader.save(source, key);
        assertEquals(source.values, key.getRaw("values"));
        assertEquals(source.values, PersistenceLoader.load(new Values(), key).values);
    }

    @Test void delegatedValuesRetainLiteralKeyIdentityOnReload() {
        Values source = new Values(); source.children.put("effect.saved", new Child("payload"));
        var key = new MemoryDataKey(); PersistenceLoader.save(source, key);
        Values loaded = PersistenceLoader.load(new Values(), key);
        assertEquals(1, loaded.children.size());
        assertEquals("effect.saved", loaded.children.get("effect.saved").name);
        assertEquals("payload", loaded.children.get("effect.saved").value);
    }

    @Test void yamlRoundTripRetainsLiteralNamesAndRemovesDeletedEntries(@TempDir Path directory) {
        Values source = new Values(); source.values.put("a", "first"); source.values.put("a.b", "second");
        source.children.put("custom.effect", new Child("payload"));
        Path file = directory.resolve("values.yml"); var storage = new YamlStorage(file.toFile());
        PersistenceLoader.save(source, storage.getKey("")); storage.save();
        var read = new YamlStorage(file.toFile()); assertTrue(read.load());
        Values loaded = PersistenceLoader.load(new Values(), read.getKey(""));
        assertEquals(source.values, loaded.values);
        assertEquals("payload", loaded.children.get("custom.effect").value);
        loaded.values.remove("a"); loaded.children.clear();
        PersistenceLoader.save(loaded, read.getKey("")); read.save(); assertTrue(storage.load());
        Values rewritten = PersistenceLoader.load(new Values(), storage.getKey(""));
        assertEquals(Map.of("a.b", "second"), rewritten.values);
        assertTrue(rewritten.children.isEmpty());
    }
}
