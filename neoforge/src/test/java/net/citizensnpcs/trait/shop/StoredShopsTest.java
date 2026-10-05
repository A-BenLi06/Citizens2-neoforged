package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.YamlStorage;

class StoredShopsTest {
    @Test
    void emptyPagesAndItemsRetainTheirPositionsAcrossSave(@TempDir Path directory) {
        StoredShops shops = new StoredShops(new YamlStorage(directory.resolve("shops.yml").toFile()));
        assertTrue(shops.loadFromDisk());
        NPCShop shop = shops.addNamedShop("pages");
        shop.getOrCreatePage(1).setItem(4, new NPCShopItem());
        shops.storeShops();
        shops.saveToDiskImmediate();
        assertTrue(shops.loadFromDisk());
        shops.load();
        NPCShop restored = shops.getGlobalShop("pages");
        assertEquals(2, restored.getPages().size());
        assertEquals(0, restored.getPages().get(0).getIndex());
        assertTrue(restored.getPages().get(0).getItems().isEmpty());
        assertEquals(1, restored.getPages().get(1).getIndex());
        assertNotNull(restored.getPages().get(1).getItem(4));
    }

    @Test
    void npcRemovalUsesBindingKeyInsteadOfDefinitionName(@TempDir Path directory) {
        StoredShops shops = new StoredShops(new YamlStorage(directory.resolve("shops.yml").toFile()));
        NPCShop definition = new NPCShop("different-name");
        shops.npcShops.put("npc-id", definition);
        shops.removeNPCShop("npc-id", definition);
        assertFalse(shops.npcShops.containsKey("npc-id"));
        assertFalse(definition.isActive());
    }

    @Test
    void npcRemovalPreservesOtherOwnersUntilLastBindingIsRemoved(@TempDir Path directory) {
        StoredShops shops = new StoredShops(new YamlStorage(directory.resolve("shops.yml").toFile()));
        NPCShop definition = shops.addNamedShop("shared");
        shops.npcShops.put("first", definition);
        shops.npcShops.put("second", definition);
        shops.removeNPCShop("first", definition);
        assertSame(definition, shops.npcShops.get("second"));
        assertSame(definition, shops.globalShops.get("shared"));
        assertTrue(definition.isActive());
        shops.globalShops.remove("shared");
        shops.removeNPCShop("second", definition);
        assertFalse(definition.isActive());
    }

    @Test
    void staleNpcRemovalPreservesReplacement(@TempDir Path directory) {
        StoredShops shops = new StoredShops(new YamlStorage(directory.resolve("shops.yml").toFile()));
        NPCShop previous = new NPCShop("npc-id");
        NPCShop replacement = shops.addNPCShop("npc-id");
        shops.removeNPCShop("npc-id", previous);
        assertSame(replacement, shops.npcShops.get("npc-id"));
        assertTrue(replacement.isActive());
        assertFalse(previous.isActive());
    }

    @Test
    void activeTradePreventsNpcBindingRemoval(@TempDir Path directory) {
        StoredShops shops = new StoredShops(new YamlStorage(directory.resolve("shops.yml").toFile()));
        NPCShop definition = shops.addNPCShop("npc-id");
        assertTrue(definition.beginTrade());
        try {
            assertThrows(IllegalStateException.class, () -> shops.removeNPCShop("npc-id", definition));
            assertSame(definition, shops.npcShops.get("npc-id"));
            assertTrue(definition.isActive());
        } finally { definition.endTrade(); }
        shops.removeNPCShop("npc-id", definition);
        assertFalse(definition.isActive());
    }

    @Test
    void publicationRetiresEverySessionEvenWhenOneCloseThrows(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        NPCShop old = shops.addNamedShop("old");
        java.util.concurrent.atomic.AtomicInteger closed = new java.util.concurrent.atomic.AtomicInteger();
        old.trackSession(closed, count -> { count.incrementAndGet(); throw new IllegalStateException("expected close failure"); });
        old.trackSession(closed, java.util.concurrent.atomic.AtomicInteger::incrementAndGet);
        storage.getKey("").setRaw("global", Map.of("new", "new"));
        var ready = shops.prepareLoad();
        assertTrue(old.isActive());
        assertEquals(0, closed.get());
        assertThrows(IllegalStateException.class, ready::publish);
        assertFalse(old.isActive());
        assertTrue(ready.isPublished());
        assertEquals(2, closed.get());
        assertNotNull(shops.getGlobalShop("new"));
        assertNull(shops.getGlobalShop("old"));
        assertThrows(IllegalStateException.class, () -> old.trackSession(closed, count -> {}));
    }

    @Test
    void tradingShopCannotBePreparedOrPublishedAway(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        NPCShop old = shops.addNamedShop("old");
        var ready = shops.prepareLoad();
        assertTrue(old.beginTrade());
        assertThrows(IllegalStateException.class, shops::prepareLoad);
        assertThrows(IllegalStateException.class, ready::publish);
        assertThrows(IllegalStateException.class, shops::closeSessions);
        assertFalse(ready.isPublished());
        assertTrue(old.isActive());
        old.endTrade();
        ready.publish();
        assertFalse(old.isActive());
        assertFalse(old.beginTrade());
    }
    @Test
    void quotedPurchaseCountsUseMapValueTypeRatherThanUuidKeyType(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        java.util.UUID buyer = java.util.UUID.randomUUID();
        storage.getKey("").setRaw("global", Map.of("shop", Map.of("pages", Map.of("0", Map.of("items",
                Map.of("0", Map.of("purchases", Map.of(buyer.toString(), "3"))))))));
        assertDoesNotThrow(shops::load);
        assertEquals(3, shops.getGlobalShop("shop").getPages().getFirst().getItem(0).purchases.get(buyer));
    }
    @Test
    void emptyShopScalarRoundTripsWithoutLosingIdentity(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        shops.addNamedShop("empty.shop");
        shops.storeShops();
        shops.saveToDiskImmediate();
        for (int i = 0; i < 2; i++) {
            assertTrue(shops.loadFromDisk());
            assertDoesNotThrow(shops::load);
            assertEquals("empty.shop", shops.getGlobalShop("empty.shop").getName());
        }
    }
    @Test
    void unknownNestedActionRejectsEntireCandidateAndPreservesFile(@TempDir Path directory) throws Exception {
        Class.forName("net.citizensnpcs.trait.ShopTrait");
        Path path = directory.resolve("shops.yml");
        YamlStorage storage = new YamlStorage(path.toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        NPCShop live = shops.addNamedShop("live");
        storage.getKey("").setRaw("global", Map.of("broken", Map.of("pages", Map.of("0", Map.of("items",
                Map.of("0", Map.of("cost", Map.of("0", Map.of("type", "unavailable-provider")))))))));
        storage.save();
        byte[] before = Files.readAllBytes(path);
        assertTrue(shops.loadFromDisk());
        assertThrows(IllegalStateException.class, shops::prepareLoad);
        assertSame(live, shops.getGlobalShop("live"));
        assertNull(shops.getGlobalShop("broken"));
        assertThrows(IllegalStateException.class, shops::storeShops);
        assertArrayEquals(before, Files.readAllBytes(path));
        storage.getKey("").setRaw("global", Map.of("repaired", Map.of("title", "ready")));
        storage.save();
        assertTrue(shops.loadFromDisk());
        assertDoesNotThrow(shops::load);
        assertNotNull(shops.getGlobalShop("repaired"));
        assertNull(shops.getGlobalShop("live"));
    }

    @Test
    void publicationReplacesDeletedEntriesAndRejectsStalePreparation(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        NPCShop old = shops.addNamedShop("removed");
        storage.getKey("").setRaw("global", Map.of("literal.name", Map.of("title", "New")));
        storage.save();
        var first = shops.prepareLoad();
        assertSame(old, shops.getGlobalShop("removed"));
        assertTrue(shops.loadFromDisk());
        assertThrows(IllegalStateException.class, first::publish);
        var next = shops.prepareLoad();
        next.publish();
        assertNull(shops.getGlobalShop("removed"));
        assertEquals("literal.name", shops.getGlobalShop("literal.name").getName());
        assertThrows(IllegalStateException.class, next::publish);
        var beforeSave = shops.prepareLoad();
        shops.storeShops();
        assertThrows(IllegalStateException.class, beforeSave::publish);
    }

    @Test
    void scalarCollectionAndUnknownEnumCannotReplaceLiveShops(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        NPCShop live = shops.addNamedShop("live");
        for (Map<String, Object> data : java.util.List.<Map<String, Object>>of(
                Map.of("pages", "bad"), Map.of("type", "NOT_A_SHOP_TYPE"),
                Map.of("pages", Map.of("0", Map.of("items", Map.of("0", Map.of("timesPurchasable", 1.5))))))) {
            storage.getKey("").setRaw("global", Map.of("bad", data));
            storage.save();
            assertTrue(shops.loadFromDisk());
            assertThrows(IllegalStateException.class, shops::load);
            assertSame(live, shops.getGlobalShop("live"));
        }
    }

    @Test
    void failedShopSerializationDoesNotPublishPartialStorage(@TempDir Path directory) {
        YamlStorage storage = new YamlStorage(directory.resolve("shops.yml").toFile());
        StoredShops shops = new StoredShops(storage);
        assertTrue(shops.loadFromDisk());
        shops.addNamedShop("existing");
        shops.storeShops();
        DataKey before = storage.getKey("").copy();
        shops.globalShops.clear();
        shops.globalShops.put("broken", new BrokenShop());

        assertThrows(IllegalStateException.class, shops::storeShops);

        assertEquals(before.getRaw(""), storage.getKey("").getRaw(""));
        shops.saveToDiskImmediate();
        assertTrue(storage.load());
        assertEquals(before.getRaw(""), storage.getKey("").getRaw(""));
        shops.globalShops.clear();
        shops.addNamedShop("recovered");
        assertDoesNotThrow(shops::storeShops);
        assertTrue(storage.getKey("").keyExists("global.recovered"));
        assertFalse(storage.getKey("").keyExists("global.existing"));
    }

    private static final class BrokenShop extends NPCShop implements Persistable {
        BrokenShop() { super("broken"); }
        @Override public void load(DataKey key) { }
        @Override public void save(DataKey key) {
            key.setString("partial", "must not be published");
            throw new IllegalStateException("expected shop serialization failure");
        }
    }
}
