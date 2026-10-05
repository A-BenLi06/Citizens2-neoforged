package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class ItemQuantityTest {
    private static long count(SimpleContainer container) {
        long count = 0;
        for (int i = 0; i < container.getContainerSize(); i++) count += container.getItem(i).getCount();
        return count;
    }

    @Test void existingPartialStackIsUsableCapacity() {
        SimpleContainer container = new SimpleContainer(new ItemStack(Items.EMERALD, 60));
        var transaction = new ItemAction(new ItemStack(Items.EMERALD)).grant(new NPCShopStorage(), null, new InventoryMultiplexer(container), 4);
        assertTrue(transaction.isPossible()); transaction.run(); assertEquals(64, count(container));
        transaction.rollback(); assertEquals(60, count(container));
    }

    @Test void mergingDoesNotStopLaterRepetitions() {
        SimpleContainer container = new SimpleContainer(3); container.setItem(0, new ItemStack(Items.EMERALD, 63));
        var transaction = new ItemAction(new ItemStack(Items.EMERALD)).grant(new NPCShopStorage(), null, new InventoryMultiplexer(container), 2);
        assertTrue(transaction.isPossible()); transaction.run(); assertEquals(65, count(container));
    }

    @Test void duplicateRequirementsShareAvailableSupply() {
        SimpleContainer container = new SimpleContainer(new ItemStack(Items.EMERALD, 20));
        InventoryMultiplexer inventory = new InventoryMultiplexer(container);
        ItemAction action = new ItemAction(new ItemStack(Items.EMERALD, 2), new ItemStack(Items.EMERALD, 3));
        assertEquals(4, action.getMaxRepeats(null, inventory));
        var transaction = action.take(new NPCShopStorage(), null, inventory, 4);
        assertTrue(transaction.isPossible()); transaction.run(); assertEquals(0, count(container));
        transaction.rollback(); assertEquals(20, count(container));
    }

    @Test void oversizedTemplateSplitsIntoLegalStacks() {
        ItemAction action = new ItemAction(new ItemStack(Items.EMERALD, 100));
        SimpleContainer one = new SimpleContainer(1);
        var impossible = action.grant(new NPCShopStorage(), null, new InventoryMultiplexer(one), 1);
        assertFalse(impossible.isPossible()); assertThrows(IllegalStateException.class, impossible::run);
        assertEquals(0, count(one));
        SimpleContainer two = new SimpleContainer(2);
        var transaction = action.grant(new NPCShopStorage(), null, new InventoryMultiplexer(two), 1);
        assertTrue(transaction.isPossible()); transaction.run();
        assertEquals(64, two.getItem(0).getCount()); assertEquals(36, two.getItem(1).getCount());
        transaction.rollback(); assertEquals(0, count(two));
    }

    @Test void largeAndNonpositiveQuantitiesCannotCreateFreeTransfers() {
        InventoryMultiplexer empty = new InventoryMultiplexer(new SimpleContainer(0));
        NPCShopStorage stock = new NPCShopStorage();
        assertFalse(new ItemAction(new ItemStack(Items.EMERALD, 1 << 30)).take(stock, null, empty, 4).isPossible());
        ItemAction many = new ItemAction(new ItemStack(Items.EMERALD), new ItemStack(Items.EMERALD),
                new ItemStack(Items.EMERALD), new ItemStack(Items.EMERALD));
        assertFalse(many.grant(stock, null, empty, 1 << 30).isPossible());
        assertFalse(many.grant(stock, null, empty, -1).isPossible());
        assertTrue(many.grant(stock, null, empty, 0).isPossible());
    }

    @Test void availableCountsDoNotWrapAndMaximumFitsApiRange() {
        SimpleContainer source = new SimpleContainer(2);
        for (int i = 0; i < 2; i++) { source.setItem(i, new ItemStack(Items.EMERALD)); source.getItem(i).setCount(Integer.MAX_VALUE); }
        assertEquals(Integer.MAX_VALUE, new ItemAction(new ItemStack(Items.EMERALD, 2)).getMaxRepeats(null, new InventoryMultiplexer(source)));
    }

    @Test void stockLimitAllowsExactFillAndExistingMerge() {
        NPCShopStorage stock = new NPCShopStorage(); stock.setUnlimited(false); stock.setInventorySizeLimit(1);
        stock.setInventory(new ArrayList<>(List.of(new ItemStack(Items.EMERALD, 56))));
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.EMERALD, 8));
        var transaction = new ItemAction(new ItemStack(Items.EMERALD, 8)).take(stock, null, new InventoryMultiplexer(source), 1);
        assertTrue(transaction.isPossible()); transaction.run();
        assertEquals(1, stock.getInventory().size()); assertEquals(64, stock.getInventory().getFirst().getCount());
        assertTrue(stock.canAdd(0)); assertFalse(stock.canAdd(Integer.MAX_VALUE)); assertFalse(stock.canAdd(-1));
        transaction.rollback(); assertEquals(56, stock.getInventory().getFirst().getCount()); assertEquals(8, count(source));
    }

    @Test void nativeSlotLimitsAndPlacementPolicyAreRespected() {
        SimpleContainer denied = new SimpleContainer(2) {
            @Override public int getMaxStackSize() { return 16; }
            @Override public boolean canPlaceItem(int slot, ItemStack stack) { return slot == 0; }
        };
        ItemAction action = new ItemAction(new ItemStack(Items.EMERALD, 20));
        assertFalse(action.grant(new NPCShopStorage(), null, new InventoryMultiplexer(denied), 1).isPossible());
        SimpleContainer allowed = new SimpleContainer(2) { @Override public int getMaxStackSize() { return 16; } };
        var transaction = action.grant(new NPCShopStorage(), null, new InventoryMultiplexer(allowed), 1);
        assertTrue(transaction.isPossible()); transaction.run();
        assertEquals(16, allowed.getItem(0).getCount()); assertEquals(4, allowed.getItem(1).getCount());
    }
}
