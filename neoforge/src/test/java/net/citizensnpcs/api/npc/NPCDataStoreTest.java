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
        public DataKey getKey(String path) { return key.getRelative(path); }
        public boolean load() { return reload.getAsBoolean(); }
        public void save() { }
        public void saveAsync() { }
    }

    /** Store tests exercise persistence callbacks; the dedicated native fixture covers real NPC lifecycle behavior. */
    private static final class Registry {
        final Map<Integer, NPC> npcs = new LinkedHashMap<>();
        final NPCDataStore store;
        final NPCRegistry api;
        int createFailure = -1;
        BiConsumer<NPC, DataKey> loader = (npc, key) -> { };
        Registry(NPCDataStore store) {
            this.store = store;
            api = (NPCRegistry) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] { NPCRegistry.class }, (proxy, method, args) -> {
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
                    case "save" -> { ((DataKey) args[0]).setString("name", "updated"); yield null; }
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
