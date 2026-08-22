package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.citizensnpcs.api.gui.MenuItems;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Covers the parts of the shop that do not need a player: the stock accounting, page renumbering, and the display item
 * the editor asks for with no viewer.
 */
public class NPCShopTest {
    @BeforeAll
    public static void bootstrapMinecraft() {
        Bootstrap.bootStrap();
    }

    @Test
    public void unlimitedStorageIgnoresStockAndBalance() {
        NPCShopStorage storage = new NPCShopStorage();
        assertTrue(storage.isUnlimited());
        storage.setBalance(50);
        assertEquals(0, storage.getBalance(), "an unlimited shop has no till to fill");
        storage.transact(contents -> contents[0] = new ItemStack(Items.STONE));
        assertTrue(storage.getInventory().isEmpty());
    }

    @Test
    public void limitedStorageKeepsWhatATransactionLeaves() {
        NPCShopStorage storage = new NPCShopStorage();
        storage.setUnlimited(false);
        storage.setInventory(new java.util.ArrayList<>(List.of(new ItemStack(Items.STONE, 4))));

        storage.transact(contents -> contents[0].setCount(2));
        assertEquals(1, storage.getInventory().size());
        assertEquals(2, storage.getInventory().get(0).getCount());

        storage.transact(contents -> contents[0] = ItemStack.EMPTY);
        assertTrue(storage.getInventory().isEmpty(), "an emptied slot must not persist as an empty stack");
    }

    @Test
    public void transactProvidesTheFreeSlotsItIsAskedFor() {
        NPCShopStorage storage = new NPCShopStorage();
        storage.setUnlimited(false);
        storage.transact(contents -> {
            assertEquals(2, contents.length, "upstream hands over a zero-length array and silently drops the items");
            contents[0] = new ItemStack(Items.DIAMOND, 3);
            contents[1] = new ItemStack(Items.EMERALD, 1);
        }, 2);
        assertEquals(2, storage.getInventory().size());
    }

    @Test
    public void balanceIsKeptOnceTheShopIsLimited() {
        NPCShopStorage storage = new NPCShopStorage();
        storage.setUnlimited(false);
        storage.setBalance(12.5);
        assertEquals(12.5, storage.getBalance(), 1e-9);
    }

    @Test
    public void removingAPageRenumbersTheRest() {
        NPCShop shop = new NPCShop("test");
        shop.getOrCreatePage(2);
        assertEquals(3, shop.getPages().size());
        shop.getOrCreatePage(1).setItem(0, new NPCShopItem());

        shop.removePage(0);
        assertEquals(2, shop.getPages().size());
        assertEquals(0, shop.getPages().get(0).getIndex());
        assertEquals(1, shop.getPages().get(1).getIndex());
        assertEquals(1, shop.getPages().get(0).getItems().size(), "the surviving page keeps its items");
    }

    @Test
    public void pageNavigationItemsFallBackToAFeather() {
        NPCShopPage page = new NPCShopPage(0);
        assertTrue(page.getNextPageItem(null, 8).is(Items.FEATHER));
        assertTrue(page.getPreviousPageItem(null, 6).is(Items.FEATHER));
    }

    @Test
    public void displayItemFillsInCostAndResultPlaceholders() {
        NPCShopItem item = new NPCShopItem();
        ItemStack display = new ItemStack(Items.DIAMOND_SWORD);
        MenuItems.setDisplayName(display, "Buy for <cost>");
        MenuItems.setLore(display, "Gives <result>");
        item.setDisplayItem(display);
        item.getCost().add(new ExperienceAction(7));
        item.getResult().add(new ItemAction(new ItemStack(Items.DIAMOND, 2)));

        ItemStack shown = item.getDisplayItem(null);
        assertNotNull(shown);
        assertEquals("Buy for 7 levels", MenuItems.getDisplayName(shown));
        assertEquals(List.of("Gives 2 Diamond"), MenuItems.getLore(shown));
    }

    @Test
    public void displayItemLeavesAnUnknownPlaceholderAlone() {
        NPCShopItem item = new NPCShopItem();
        ItemStack display = new ItemStack(Items.PAPER);
        MenuItems.setDisplayName(display, "Limit <times_purchasable>, keep <other>");
        item.setDisplayItem(display);
        item.timesPurchasable = 3;

        assertEquals("Limit 3, keep <other>", MenuItems.getDisplayName(item.getDisplayItem(null)));
    }

    @Test
    public void anItemWithNoDisplayStackIsNotShown() {
        assertNull(new NPCShopItem().getDisplayItem(null));
    }

    @Test
    public void shopTypeSlotsSitOnTheBottomRow() {
        assertEquals(45, ShopType.DEFAULT.getInventorySize());
        assertEquals(39, ShopType.DEFAULT.getPrevSlotIndex());
        assertEquals(40, ShopType.DEFAULT.getEditSlotIndex());
        assertEquals(41, ShopType.DEFAULT.getNextSlotIndex());
        // a one-row shop has nowhere else to put them
        assertEquals(9, ShopType.CHEST_1X9.getInventorySize());
        assertEquals(7, ShopType.CHEST_1X9.getPrevSlotIndex());
    }
}
