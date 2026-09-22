package net.citizensnpcs.api.util;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import net.citizensnpcs.api.persistence.PersistenceLoader;
import net.citizensnpcs.trait.shop.ItemAction;
import net.citizensnpcs.trait.shop.NPCShopStorage;
import net.citizensnpcs.trait.shop.NPCShopItem;
import net.citizensnpcs.trait.shop.NPCShopItemEditor;
import net.citizensnpcs.trait.DropsTrait.ItemDrop;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class StoredItemListTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void reflectiveItemListsRetainUnavailableEntriesAndCannotChargeOrReward() {
        DataKey root = fixture("items");
        Object before = root.copy().getRaw("items");
        ItemAction action = PersistenceLoader.load(ItemAction.class, root);
        assertNotNull(action); assertEquals(2, action.items.size());
        assertNull(action.items.get(0)); assertTrue(StoredItemList.hasUnavailable(action.items));
        var container = new SimpleContainer(new ItemStack(Items.DIAMOND, 20));
        var inventory = new InventoryMultiplexer(container);
        assertFalse(action.take(new NPCShopStorage(), null, inventory, 1).isPossible());
        assertFalse(action.grant(new NPCShopStorage(), null, inventory, 1).isPossible());
        assertEquals(0, action.getMaxRepeats(null, inventory));
        assertEquals(20, container.getItem(0).getCount());
        DataKey saved = new MemoryDataKey(); PersistenceLoader.save(action, saved);
        assertEquals(before, saved.getRaw("items"));
        action.items.remove(0);
        assertFalse(StoredItemList.hasUnavailable(action.items));
        PersistenceLoader.save(action, saved);
        assertEquals(1, PersistenceLoader.load(ItemAction.class, saved).items.size());
    }

    @Test void failedListEncodingKeepsPreviousRecordEvenForOrdinaryApiLists() {
        DataKey root = fixture("items"); Object before = root.copy().getRaw("");
        ItemAction action = new ItemAction();
        ItemStack invalid = new ItemStack(Items.DIAMOND_SWORD); invalid.set(DataComponents.DAMAGE, -1);
        action.items = List.of(new ItemStack(Items.GOLD_INGOT), invalid);
        assertThrows(IllegalStateException.class, () -> PersistenceLoader.save(action, root));
        assertEquals(before, root.getRaw(""));
    }

    @Test void limitedShopStockWithUnavailableItemsCannotBeRewrittenByATrade() {
        DataKey root = fixture("inventory"); root.setBoolean("unlimited", false);
        NPCShopStorage stock = PersistenceLoader.load(NPCShopStorage.class, root);
        assertNotNull(stock); assertTrue(stock.hasUnavailableItems());
        var before = new MemoryDataKey(); PersistenceLoader.save(stock, before);
        var action = new ItemAction(new ItemStack(Items.DIAMOND));
        var inventory = new InventoryMultiplexer(new SimpleContainer(9));
        assertFalse(action.grant(stock, null, inventory, 1).isPossible());
        assertFalse(action.take(stock, null, inventory, 1).isPossible());
        assertThrows(IllegalStateException.class, () -> stock.transact(items -> items[0] = new ItemStack(Items.STONE)));
        var after = new MemoryDataKey(); PersistenceLoader.save(stock, after);
        assertEquals(before.getRaw(""), after.getRaw(""));
        stock.setInventory(List.of(new ItemStack(Items.EMERALD)));
        assertFalse(stock.hasUnavailableItems());
    }

    @Test void rawYamlListsAndExplicitSetRetainPositionUntilEdited() {
        DataKey source = fixture("items");
        DataKey listRoot = new MemoryDataKey().getRelative("items");
        listRoot.setRaw("", List.of(source.getRaw("items.0"), source.getRaw("items.1")));
        StoredItemList items = StoredItemList.load(listRoot);
        assertEquals(2, items.size()); assertTrue(StoredItemList.hasUnavailable(items));
        items.set(0, new ItemStack(Items.STONE));
        assertFalse(StoredItemList.hasUnavailable(items));
        var saved = new MemoryDataKey(); StoredItemList.save(items, saved);
        assertEquals(Items.STONE, ItemStorage.loadItemStack(saved.getRelative("0")).getItem());
    }

    private static DataKey fixture(String key) {
        DataKey root = new MemoryDataKey();
        root.setString(key + ".0.nbt", "{id:'missing:item',count:1}");
        ItemStorage.saveItem(root.getRelative(key + ".1"), new ItemStack(Items.DIAMOND, 4));
        return root;
    }

    @Test void scalarDropAndShopDisplayRecordsAreRetainedAndCloneEditsAreIndependent() {
        DataKey source = new MemoryDataKey(); source.setDouble("chance", 0.75);
        source.setString("drop.nbt", "{id:'missing:drop',count:1}");
        ItemDrop drop = PersistenceLoader.load(ItemDrop.class, source);
        assertTrue(drop.isUnavailable()); assertNull(drop.getDrop());
        DataKey saved = new MemoryDataKey(); PersistenceLoader.save(drop, saved);
        assertEquals(source.getRaw(""), saved.getRaw(""));

        DataKey display = new MemoryDataKey(); display.setString("display.nbt", "{id:'missing:display',count:1}");
        NPCShopItem shopItem = PersistenceLoader.load(NPCShopItem.class, display);
        assertTrue(shopItem.hasUnresolvedDisplay());
        var callback = new java.util.concurrent.atomic.AtomicReference<NPCShopItem>();
        new NPCShopItemEditor(shopItem, callback::set).onClose(null);
        assertSame(shopItem, callback.get(), "closing an unavailable display does not remove the shop entry");
        NPCShopItem copy = shopItem.clone(); copy.setDisplayItem(null);
        assertFalse(copy.hasUnresolvedDisplay()); assertTrue(shopItem.hasUnresolvedDisplay());
        DataKey originalSaved = new MemoryDataKey(); PersistenceLoader.save(shopItem, originalSaved);
        assertEquals(display.getRaw("display"), originalSaved.getRaw("display"));
    }

    @Test void clonedActionsKeepIndependentUnavailableEntriesAndNativeStacks() {
        ItemAction original = PersistenceLoader.load(ItemAction.class, fixture("items"));
        ItemAction copy = original.clone(); copy.items.remove(0); copy.items.get(0).setCount(7);
        assertEquals(2, original.items.size()); assertTrue(StoredItemList.hasUnavailable(original.items));
        assertEquals(4, original.items.get(1).getCount());
    }
}
