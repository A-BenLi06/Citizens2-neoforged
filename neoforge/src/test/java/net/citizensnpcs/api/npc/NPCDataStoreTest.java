package net.citizensnpcs.api.npc;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Storage;
import net.minecraft.server.Bootstrap;

class NPCDataStoreTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void allocationReservesUnloadedAndMalformedDefinitions() {
        MemoryStorage storage = new MemoryStorage();
        storage.key.setMap("npc", new LinkedHashMap<>(Map.of("0", Map.of("name", "missing provider"), "7", "damaged record")));
        storage.key.setInt("last-created-npc-id", 0);
        var before = storage.key.getValuesDeep();
        var store = new SimpleNPCDataStore(storage);
        Registry registry = new Registry(store);
        assertEquals(8, store.createUniqueNPCId(registry.api));
        assertEquals(before.get("npc"), storage.key.getRaw("npc"));
        assertEquals(9, store.createUniqueNPCId(registry.api));
    }

    @Test void exhaustedHighWaterMarkUsesAFreeNonnegativeId() {
        MemoryStorage storage = new MemoryStorage();
        storage.key.setMap("npc", Map.of("0", Map.of("name", "reserved"), "2147483647", Map.of("name", "last")));
        storage.key.setInt("last-created-npc-id", Integer.MAX_VALUE);
        var store = new SimpleNPCDataStore(storage);
        Registry registry = new Registry(store);
        registry.add(1, UUID.randomUUID());
        assertEquals(2, store.createUniqueNPCId(registry.api));
        assertEquals(3, store.createUniqueNPCId(registry.api));
    }

    @Test void memoryStoreRespectsExplicitlyRegisteredIds() {
        var store = new MemoryNPCDataStore();
        Registry registry = new Registry(store);
        NPC original = registry.add(0, UUID.randomUUID());
        registry.add(9, UUID.randomUUID());
        assertEquals(10, store.createUniqueNPCId(registry.api));
        assertSame(original, registry.api.getById(0));
        registry.add(Integer.MAX_VALUE, UUID.randomUUID());
        assertEquals(11, store.createUniqueNPCId(registry.api));
        assertEquals(12, store.createUniqueNPCId(registry.api));
    }

    @Test void failedReloadRestoresThePreviousStoreSnapshot() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "2", UUID.randomUUID());
        var before = storage.key.getValuesDeep();
        storage.reload = () -> { storage.key.setRaw("", Map.of("broken", true)); return false; };
        assertThrows(IllegalStateException.class, () -> new SimpleNPCDataStore(storage).reloadFromSource());
        assertEquals(before, storage.key.getValuesDeep());
        storage.reload = () -> { storage.key.setRaw("", Map.of("npc", "invalid root")); return true; };
        assertThrows(IllegalStateException.class, () -> new SimpleNPCDataStore(storage).reloadFromSource());
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void invalidRecordCannotStopLaterValidRecords() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID()).setString("uuid", "invalid-uuid");
        record(storage, "1", UUID.randomUUID()).setString("traits.type", "missing_provider:animal");
        record(storage, "2", UUID.randomUUID());
        record(storage, "3", UUID.randomUUID()).setString("traitnames", "");
        record(storage, "4", UUID.randomUUID());
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        assertDoesNotThrow(() -> registry.store.loadInto(registry.api));
        assertEquals(Set.of(2, 4), registry.npcs.keySet());
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void failedCreationAndLoadCannotDeleteSavedDefinitions() {
        MemoryStorage storage = new MemoryStorage();
        for (int id = 0; id < 3; id++) record(storage, Integer.toString(id), UUID.randomUUID());
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.createFailure = 0;
        registry.loader = (npc, key) -> { if (npc.getId() == 1) throw new IllegalStateException("fixture load failure"); };
        assertDoesNotThrow(() -> registry.store.loadInto(registry.api));
        assertEquals(Set.of(2), registry.npcs.keySet());
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void loadingUsesDetachedDataAndResolvedLegacyType() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID()).setString("traits.type", "Zombie Villager");
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.loader = (npc, key) -> {
            assertEquals("minecraft:zombie_villager", key.getString("traits.type"));
            key.setString("metadata.loader-change", "transient");
        };
        registry.store.loadInto(registry.api);
        assertEquals(Set.of(0), registry.npcs.keySet());
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void ambiguousIdsAndDuplicateUuidsNeverReplaceLoadedNpcs() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "01", UUID.randomUUID());
        record(storage, "1", UUID.randomUUID());
        UUID uuid = UUID.randomUUID();
        record(storage, "2", uuid);
        record(storage, "3", uuid);
        record(storage, "4", UUID.randomUUID());
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        assertEquals(Set.of(2, 4), registry.npcs.keySet());
        assertSame(registry.api.getById(2), registry.api.getByUniqueId(uuid));
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void aUniqueNumericKeyKeepsItsOriginalSpellingOnSaveAndRemoval() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "007", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        registry.store.store(registry.api.getById(7));
        assertFalse(storage.key.keyExists("npc.7"));
        assertEquals("updated", storage.key.getString("npc.007.name"));
        registry.store.clearData(registry.api.getById(7));
        assertFalse(storage.key.keyExists("npc.007"));
    }

    @Test void aRestoreCannotPublishItsPartialRecord() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.loader = (npc, key) -> assertThrows(IllegalStateException.class, () -> registry.store.store(npc));
        registry.store.loadInto(registry.api);
        assertEquals(before, storage.key.getValuesDeep());
        assertEquals(Set.of(0), registry.npcs.keySet());
    }

    @Test void failedSingleSavePreservesTheWholeRecord() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "007", UUID.randomUUID()).setMap("unknown", Map.of("literal.key", java.util.List.of("retained")));
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        registry.writer = (npc, key) -> {
            key.setString("name", "partial"); key.removeKey("unknown");
            throw new IllegalStateException("writer failed");
        };
        assertThrows(IllegalStateException.class, () -> registry.store.store(registry.api.getById(7)));
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void failedBatchDoesNotPublishEarlierNpcs() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID()); record(storage, "1", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        registry.writer = (npc, key) -> {
            key.setString("name", "changed-" + npc.getId());
            if (npc.getId() == 1) throw new IllegalStateException("second writer failed");
        };
        assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
        registry.writer = (npc, key) -> key.setString("name", "retry-" + npc.getId());
        registry.store.storeAll(registry.api);
        assertEquals("retry-0", storage.key.getString("npc.0.name"));
        assertEquals("retry-1", storage.key.getString("npc.1.name"));
    }

    @Test void saveCallbacksCannotPublishOrMutateTheStoreRecursively() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        boolean[] insideWriter = { false };
        registry.writer = (npc, key) -> {
            // A missing admission guard must fail the assertion without recursively running this fixture.
            if (insideWriter[0]) return;
            insideWriter[0] = true;
            try {
                assertAll(
                    () -> assertThrows(IllegalStateException.class, registry.store::saveToDiskImmediate),
                    () -> assertThrows(IllegalStateException.class, registry.store::saveToDisk),
                    () -> assertThrows(IllegalStateException.class, registry.store::reloadFromSource),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.store(npc)),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api)),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.loadInto(registry.api)),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.clearData(npc)),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.clearTraitData(java.util.List.of("type"))),
                    () -> assertThrows(IllegalStateException.class, () -> registry.store.createUniqueNPCId(registry.api)));
                key.setString("name", "complete");
            } finally {
                insideWriter[0] = false;
            }
        };
        registry.store.storeAll(registry.api);
        assertEquals("complete", storage.key.getString("npc.0.name"));
        assertEquals(0, storage.flushes);
    }

    @Test void retainedWriterKeysCannotChangePublishedRecords() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        DataKey[] retained = { null };
        registry.writer = (npc, key) -> { retained[0] = key; key.setString("name", "published"); };
        registry.store.store(registry.api.getById(0));
        retained[0].setString("name", "late edit");
        assertEquals("published", storage.key.getString("npc.0.name"));
        registry.store.storeAll(registry.api);
        retained[0].setString("name", "late batch edit");
        assertEquals("published", storage.key.getString("npc.0.name"));
    }

    @Test void skippedSavesDoNotCreateEmptyNpcDefinitions() {
        MemoryStorage storage = new MemoryStorage();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        NPC npc = registry.add(2, UUID.randomUUID());
        registry.writer = (unused, key) -> { };
        registry.store.store(npc);
        registry.store.storeAll(registry.api);
        assertNull(storage.key.getRaw("npc.2"));
    }

    @Test void aReplacementDuringBatchSerializationCannotPublishTheOldNpc() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        NPC previous = registry.api.getById(0);
        registry.writer = (npc, key) -> { key.setString("name", "obsolete"); registry.add(0, UUID.randomUUID()); };
        assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
        assertNotSame(previous, registry.api.getById(0), "an external callback's live mutation is not rolled back");
    }

    @Test void absoluteWriterReferencesPreserveTheirDocumentContext() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "007", UUID.randomUUID());
        storage.key.setString("global.provider.value", "original");
        storage.key.setMap("unknown", Map.of("literal.key", java.util.List.of("retained")));
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.loader = (npc, key) -> {
            assertEquals("original", key.getFromRoot("global.provider").getString("value"));
            key.getFromRoot("global.provider").setString("value", "load-only");
        };
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        registry.writer = (npc, key) -> {
            assertEquals("npc.007", key.getPath());
            key.getFromRoot("global.provider").setString("value", "changed");
            throw new IllegalStateException("global writer failed");
        };
        assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
        registry.writer = (npc, key) -> key.getFromRoot("global.provider").setString("value", "complete");
        registry.store.storeAll(registry.api);
        assertEquals("complete", storage.key.getString("global.provider.value"));
        assertEquals(before.get("npc"), storage.key.getRaw("npc"));
        assertEquals(before.get("unknown"), storage.key.getRaw("unknown"));
    }

    @Test void singleSaveCannotPublishAReplacedNpcAndCanRetryTheReplacement() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        NPC previous = registry.api.getById(0);
        registry.writer = (npc, key) -> { key.setString("name", "obsolete"); registry.add(0, UUID.randomUUID()); };
        assertThrows(IllegalStateException.class, () -> registry.store.store(previous));
        assertEquals(before, storage.key.getValuesDeep());
        assertThrows(IllegalStateException.class, () -> registry.store.store(previous));
        registry.writer = (npc, key) -> key.setString("name", "replacement");
        registry.store.store(registry.api.getById(0));
        assertEquals("replacement", storage.key.getString("npc.0.name"));
    }

    @Test void addedAndRemovedRegistryMembersInvalidateTheBatch() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        registry.writer = (npc, key) -> { key.setString("name", "obsolete"); registry.add(1, UUID.randomUUID()); };
        assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
        registry.npcs.remove(1);
        registry.writer = (npc, key) -> { key.setString("name", "obsolete"); registry.npcs.clear(); };
        assertThrows(IllegalStateException.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
    }

    @Test void batchSaveRetainsUnknownAndUnloadedDefinitions() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "007", UUID.randomUUID());
        storage.key.setRaw("npc.15", "damaged record");
        storage.key.setMap("npc.20", Map.of("name", "unavailable", "traits", Map.of("type", "missing:entity")));
        storage.key.setMap("npc.literal-key", Map.of("literal.key", java.util.List.of("retained")));
        var before = storage.key.getValuesDeep();
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        registry.store.storeAll(registry.api);
        assertEquals(Set.of(7), registry.npcs.keySet());
        assertEquals("updated", storage.key.getString("npc.007.name"));
        Map<?, ?> previousNpcs = (Map<?, ?>) before.get("npc");
        for (String key : java.util.List.of("15", "20", "literal-key"))
            assertEquals(previousNpcs.get(key), storage.key.getRaw("npc." + key));
    }

    @Test void writerErrorsReleaseTheSaveGuardWithoutPublishing() {
        MemoryStorage storage = new MemoryStorage();
        record(storage, "0", UUID.randomUUID());
        Registry registry = new Registry(new SimpleNPCDataStore(storage));
        registry.store.loadInto(registry.api);
        var before = storage.key.getValuesDeep();
        registry.writer = (npc, key) -> { key.setString("name", "partial"); throw new AssertionError("writer error"); };
        assertThrows(AssertionError.class, () -> registry.store.storeAll(registry.api));
        assertEquals(before, storage.key.getValuesDeep());
        registry.writer = (npc, key) -> key.setString("name", "retry");
        registry.store.storeAll(registry.api);
        assertEquals("retry", storage.key.getString("npc.0.name"));
    }

    private static DataKey record(MemoryStorage storage, String id, UUID uuid) {
        DataKey key = storage.key.getRelative("npc." + id);
        key.setString("name", "stored"); key.setString("uuid", uuid.toString());
        key.setString("traitnames", "type,spawned"); key.setString("traits.type", "PIG");
        key.setBoolean("traits.spawned", false);
        return key;
    }

    private static final class MemoryStorage implements Storage {
        final MemoryDataKey key = new MemoryDataKey(new LinkedHashMap<>());
        java.util.function.BooleanSupplier reload = () -> true;
        int flushes;
        public DataKey getKey(String path) { return key.getRelative(path); }
        public boolean load() { return reload.getAsBoolean(); }
        public void save() { flushes++; }
        public void saveAsync() { flushes++; }
    }

    /** Store tests exercise persistence callbacks; the dedicated native fixture covers real NPC lifecycle behavior. */
    private static final class Registry {
        final Map<Integer, NPC> npcs = new LinkedHashMap<>();
        final NPCDataStore store;
        final NPCRegistry api;
        int createFailure = -1;
        BiConsumer<NPC, DataKey> loader = (npc, key) -> { };
        BiConsumer<NPC, DataKey> writer = (npc, key) -> key.setString("name", "updated");
        Registry(NPCDataStore store) {
            this.store = store;
            api = (NPCRegistry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { NPCRegistry.class }, (proxy, method, args) -> {
                if (method.isDefault()) return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                return switch (method.getName()) {
                    case "iterator" -> npcs.values().iterator();
                    case "getById" -> npcs.get((Integer) args[0]);
                    case "getByUniqueId" -> npcs.values().stream().filter(npc -> npc.getUniqueId().equals(args[0])).findFirst().orElse(null);
                    case "createNPC" -> {
                        NPC created = add((Integer) args[2], (UUID) args[1]);
                        if (created.getId() == createFailure) { created.destroy(); throw new IllegalStateException("fixture creation failure"); }
                        yield created;
                    }
                    case "getName" -> "store-test";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
        }
        NPC add(int id, UUID uuid) {
            NPC npc = (NPC) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { NPC.class }, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "getId" -> id;
                    case "getUniqueId" -> uuid;
                    case "getOwningRegistry" -> api;
                    case "load" -> { loader.accept((NPC) proxy, (DataKey) args[0]); yield null; }
                    case "save", "saveSnapshot" -> { writer.accept((NPC) proxy, (DataKey) args[0]); yield null; }
                    case "destroy" -> { if (npcs.remove(id, proxy)) store.clearData((NPC) proxy); yield null; }
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "StoreTestNPC[" + id + "]";
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
            npcs.put(id, npc);
            return npc;
        }
    }
}
