package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoredItemsTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void unavailableNativeRecordsSurviveSnapshotsWithIndependentNestedData() {
        DataKey source = unavailable();
        var notes = new ArrayList<>(List.of("original"));
        source.setRaw("extension", Map.of("literal.key", notes));
        var items = new StoredItems<Integer>();
        assertNull(items.load(5, source));
        notes.set(0, "changed source");
        DataKey saved = new MemoryDataKey(); items.save(5, saved, null);
        assertEquals(Map.of("literal.key", List.of("original")), saved.getRaw("extension"));
        ((List<String>) ((Map<?, ?>) saved.getRaw("extension")).get("literal.key")).set(0, "changed snapshot");
        DataKey again = new MemoryDataKey(); items.save(5, again, ItemStack.EMPTY);
        assertEquals(Map.of("literal.key", List.of("original")), again.getRaw("extension"));
        assertEquals(Set.of(5), items.keys());
        assertThrows(UnsupportedOperationException.class, () -> items.keys().clear());
    }

    @Test void explicitClearAndReplacementRemoveDeferredDefinitions() {
        var items = new StoredItems<Integer>();
        assertNull(items.load(1, unavailable()));
        DataKey saved = new MemoryDataKey();
        items.clear(1); items.save(1, saved, null);
        assertFalse(saved.keyExists(""));
        items.load(1, unavailable());
        items.save(1, saved, new ItemStack(Items.DIAMOND, 3));
        assertFalse(items.contains(1));
        assertEquals(3, ItemStorage.loadItemStack(saved).getCount());
        items.save(1, saved, null);
        assertFalse(saved.keyExists(""));
    }

    @Test void failedReplacementDoesNotForgetTheDeferredRecord() {
        var items = new StoredItems<Integer>();
        DataKey original = unavailable(); items.load(1, original);
        ItemStack invalid = new ItemStack(Items.DIAMOND_SWORD); invalid.set(DataComponents.DAMAGE, -1);
        DataKey target = new MemoryDataKey();
        assertThrows(IllegalStateException.class, () -> items.save(1, target, invalid));
        assertTrue(items.contains(1));
        items.save(1, target, null);
        assertEquals(original.getRaw(""), target.getRaw(""));
    }

    @Test void aReturningLegacyReaderReceivesEitherOriginalBlobLayout() {
        var before = ItemStorage.getLegacyItemMetaReader();
        try {
            ItemStorage.setLegacyItemMetaReader(null);
            for (String path : List.of("meta", "meta.encoded-meta")) {
                DataKey original = new MemoryDataKey();
                original.setString("type", "diamond_sword"); original.setInt("amount", 2);
                original.setString(path, "encoded fixture");
                var items = new StoredItems<Integer>();
                assertNull(items.load(0, original));
                DataKey persisted = new MemoryDataKey(); items.save(0, persisted, null);
                assertEquals(original.getRaw(""), persisted.getRaw(""));
                ItemStorage.setLegacyItemMetaReader((encoded, stack) -> {
                    assertEquals("encoded fixture", encoded);
                    stack.set(DataComponents.CUSTOM_MODEL_DATA, new net.minecraft.world.item.component.CustomModelData(52));
                    return true;
                });
                ItemStack loaded = items.load(0, persisted);
                assertNotNull(loaded); assertEquals(2, loaded.getCount());
                assertEquals(52, loaded.get(DataComponents.CUSTOM_MODEL_DATA).value());
                assertFalse(items.contains(0));
                items.save(0, persisted, loaded);
                assertFalse(persisted.keyExists("meta"));
                ItemStorage.setLegacyItemMetaReader(null);
            }
        } finally { ItemStorage.setLegacyItemMetaReader(before); }
    }

    @Test void failedReaderMutationsAreNotExposedAndSourceIsKept() {
        var before = ItemStorage.getLegacyItemMetaReader();
        try {
            ItemStorage.setLegacyItemMetaReader((encoded, stack) -> { stack.setCount(50); return false; });
            DataKey original = new MemoryDataKey(); original.setString("type", "stone"); original.setString("meta", "bad");
            var items = new StoredItems<Integer>(); assertNull(items.load(0, original));
            DataKey target = new MemoryDataKey(); items.save(0, target, null);
            assertEquals(original.getRaw(""), target.getRaw(""));
        } finally { ItemStorage.setLegacyItemMetaReader(before); }
    }

    @Test void emptyAndExplicitAirSlotsDoNotBecomeUnavailable() {
        var items = new StoredItems<Integer>();
        assertNull(items.load(0, new MemoryDataKey()));
        DataKey air = new MemoryDataKey(); air.setString("type", "AIR"); air.setInt("amount", 0);
        assertNull(items.load(1, air));
        DataKey nativeEmpty = new MemoryDataKey(); nativeEmpty.setString("nbt", "{}");
        assertNull(items.load(2, nativeEmpty));
        assertTrue(items.keys().isEmpty());
    }

    private static DataKey unavailable() {
        DataKey key = new MemoryDataKey();
        key.setString("nbt", "{id:'minecraft:paper',count:1,components:{'unavailable:component':{value:1}}}");
        key.setString("editable_components.display_name", "pending"); key.setBoolean("editable_components.edited", true);
        return key;
    }
}
