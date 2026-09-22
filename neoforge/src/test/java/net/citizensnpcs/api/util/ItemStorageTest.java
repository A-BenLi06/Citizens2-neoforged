package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

/**
 * Covers {@link ItemStorage}'s two read paths: the current SNBT format, and the one-way migration of saves written by
 * the Bukkit plugin.
 * <p>
 * These run without a server, so {@code ItemStorage} falls back to an empty registry access. That is enough for plain
 * items and for every component reachable from {@code BuiltInRegistries}; items carrying enchantments need a live
 * server and are not covered here.
 */
public class ItemStorageTest {
    @BeforeAll
    public static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    public void currentFormatRoundTrips(@TempDir Path dir) {
        ItemStack original = new ItemStack(Items.DIAMOND_SWORD, 3);
        original.set(DataComponents.DAMAGE, 42);

        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "items.yml"));
        ItemStorage.saveItem(storage.getKey("item"), original);
        storage.save();

        YamlStorage reloaded = new YamlStorage(new File(dir.toFile(), "items.yml"));
        assertTrue(reloaded.load());
        ItemStack loaded = ItemStorage.loadItemStack(reloaded.getKey("item"));

        assertNotNull(loaded);
        assertEquals(Items.DIAMOND_SWORD, loaded.getItem());
        assertEquals(3, loaded.getCount());
        assertEquals(42, loaded.getOrDefault(DataComponents.DAMAGE, 0));
    }

    @Test
    public void emptyAndNullYieldNull(@TempDir Path dir) {
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "empty.yml"));
        ItemStorage.saveItem(storage.getKey("a"), null);
        ItemStorage.saveItem(storage.getKey("b"), ItemStack.EMPTY);

        assertNull(ItemStorage.loadItemStack(storage.getKey("a")));
        assertNull(ItemStorage.loadItemStack(storage.getKey("b")));
    }

    @Test
    public void legacyBukkitEntryMigrates(@TempDir Path dir) {
        // exactly what the Bukkit plugin writes: lowercase type, amount, durability, plain-text editable components
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "legacy.yml"));
        DataKey key = storage.getKey("item");
        key.setString("type", "diamond_sword");
        key.setInt("amount", 1);
        key.setInt("durability", 7);
        key.setString("editable_components.display_name", "&aExcalibur");
        key.setString("editable_components.lore", "line one<br>line two");
        key.setBoolean("editable_components.edited", false);

        ItemStack loaded = ItemStorage.loadItemStack(key);

        assertNotNull(loaded);
        assertEquals(Items.DIAMOND_SWORD, loaded.getItem());
        assertEquals(1, loaded.getCount());
        assertEquals(7, loaded.getOrDefault(DataComponents.DAMAGE, 0));
        assertEquals("Excalibur", loaded.get(DataComponents.CUSTOM_NAME).getString());
        ItemLore lore = loaded.get(DataComponents.LORE);
        assertNotNull(lore);
        assertEquals(2, lore.lines().size());
        assertEquals("line one", lore.lines().get(0).getString());
    }

    @Test
    public void legacyUppercaseMaterialNameMigrates(@TempDir Path dir) {
        // pre-1.13 Bukkit saves store the Material enum name
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "legacy-upper.yml"));
        DataKey key = storage.getKey("item");
        key.setString("type", "GOLDEN_APPLE");
        key.setInt("amount", 5);

        ItemStack loaded = ItemStorage.loadItemStack(key);

        assertNotNull(loaded);
        assertEquals(Items.GOLDEN_APPLE, loaded.getItem());
        assertEquals(5, loaded.getCount());
    }

    @Test
    public void legacyUnknownTypeYieldsNull(@TempDir Path dir) {
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "legacy-unknown.yml"));
        DataKey key = storage.getKey("item");
        key.setString("type", "definitely_not_an_item");
        key.setInt("amount", 1);

        assertNull(ItemStorage.loadItemStack(key));
    }

    @Test
    public void savingClearsLegacyKeys(@TempDir Path dir) {
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "resave.yml"));
        DataKey key = storage.getKey("item");
        key.setString("type", "diamond_sword");
        key.setInt("amount", 1);
        key.setString("meta", "b64blob");

        ItemStack migrated = ItemStorage.loadItemStack(key);
        assertNotNull(migrated);
        ItemStorage.saveItem(key, migrated);

        assertFalse(key.keyExists("type"), "legacy keys must not survive a re-save");
        assertFalse(key.keyExists("meta"));
        assertTrue(key.keyExists("nbt"));
    }

    @Test
    public void editedComponentsOverrideStoredNbt(@TempDir Path dir) {
        ItemStack original = new ItemStack(Items.STONE);
        YamlStorage storage = new YamlStorage(new File(dir.toFile(), "edited.yml"));
        DataKey key = storage.getKey("item");
        ItemStorage.saveItem(key, original);

        // a server owner hand-edits saves.yml and flips the flag
        key.setString("editable_components.display_name", "&cRenamed");
        key.setBoolean("editable_components.edited", true);

        ItemStack loaded = ItemStorage.loadItemStack(key);
        assertNotNull(loaded);
        assertEquals("Renamed", loaded.get(DataComponents.CUSTOM_NAME).getString());
        assertFalse(key.getBoolean("editable_components.edited", true), "flag is cleared once applied");
    }

    @Test
    public void failedNativeEncodingPreservesCompletePreviousRecord() {
        for (boolean legacy : List.of(false, true)) {
            DataKey key = new MemoryDataKey().getRelative("item");
            if (legacy) {
                key.setString("type", "diamond_sword");
                key.setInt("amount", 1);
                key.setString("meta.encoded-meta", "original encoded metadata");
            } else {
                ItemStorage.saveItem(key, new ItemStack(Items.DIAMOND));
            }
            key.setString("editable_components.display_name", "pending edit");
            key.setBoolean("editable_components.edited", true);
            key.setString("provider.value", "retained");
            Object before = key.copy().getRaw("");
            ItemStack invalid = new ItemStack(Items.DIAMOND_SWORD);
            invalid.set(DataComponents.DAMAGE, -1);
            AtomicInteger hooks = new AtomicInteger();
            ItemStorage.setHooks((root, stack) -> hooks.incrementAndGet(), null);
            try {
                assertThrows(IllegalStateException.class, () -> ItemStorage.saveItem(key, invalid));
                assertEquals(before, key.getRaw(""));
                assertEquals(0, hooks.get(), "failed encoding does not publish a serialise event");
            } finally {
                ItemStorage.setHooks(null, null);
            }
        }
    }

    @Test
    public void invalidNativeComponentsDoNotBecomePartialItems() {
        for (String components : List.of("{'minecraft:damage':-1}", "{'missing:component':{value:1}}",
                "{'minecraft:damage':'invalid'}")) {
            DataKey key = new MemoryDataKey().getRelative("item");
            key.setString("nbt", "{id:'minecraft:diamond_sword',count:1,components:" + components + "}");
            key.setString("editable_components.display_name", "pending edit");
            key.setBoolean("editable_components.edited", true);
            Object before = key.copy().getRaw("");
            assertNull(ItemStorage.loadItemStack(key));
            assertEquals(before, key.getRaw(""), "failed read does not consume edits or change the source");
        }
    }

    @Test
    public void successfulReplacementAndExplicitRemovalStillClearOldFields() {
        DataKey key = new MemoryDataKey().getRelative("item");
        key.setString("type_key", "stone");
        key.setString("meta.encoded-meta", "old data");
        key.setString("provider.value", "retained");
        ItemStorage.saveItem(key, new ItemStack(Items.GOLD_INGOT, 2));
        assertFalse(key.keyExists("type_key"));
        assertFalse(key.keyExists("meta"));
        assertEquals(2, ItemStorage.loadItemStack(key).getCount());
        assertEquals("retained", key.getString("provider.value"));
        ItemStorage.saveItem(key, null);
        assertFalse(key.keyExists("nbt"));
        assertFalse(key.keyExists("editable_components"));
        assertEquals("retained", key.getString("provider.value"));
    }
}
