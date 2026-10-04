package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.YamlStorage;

class StoredShopsTest {
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
