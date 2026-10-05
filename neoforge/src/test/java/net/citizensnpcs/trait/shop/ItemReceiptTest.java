package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

class ItemReceiptTest {
    private static ItemStack named(String lore) {
        ItemStack item = new ItemStack(Items.DIAMOND_SWORD);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Shared name"));
        if (lore != null) item.set(DataComponents.LORE, new ItemLore(List.of(Component.literal(lore))));
        return item;
    }
    private static ItemAction loose(ItemStack template) {
        ItemAction action = new ItemAction(template); action.metaFilter = List.of("custom_name"); action.requireUndamaged = false; return action;
    }
    private static NPCShopStorage limited(ItemStack... items) {
        NPCShopStorage stock = new NPCShopStorage(); stock.setUnlimited(false); stock.setInventory(new ArrayList<>(List.of(items))); return stock;
    }

    @Test void paidComponentsReachStockAndReturnExactlyOnce() {
        ItemStack actual = named("Original lore"); actual.setDamageValue(7); actual.set(DataComponents.REPAIR_COST, 5);
        SimpleContainer source = new SimpleContainer(3); source.setItem(0, actual.copy());
        NPCShopStorage stock = limited();
        var payment = loose(named(null)).take(stock, null, new InventoryMultiplexer(source), 1);
        assertTrue(payment.isPossible()); payment.run();
        assertTrue(source.isEmpty()); assertTrue(ItemStack.matches(actual, stock.getInventory().getFirst()));
        payment.rollback(); assertTrue(ItemStack.matches(actual, source.getItem(0))); assertTrue(stock.getInventory().isEmpty());
        payment.rollback(); assertTrue(source.getItem(1).isEmpty());
    }

    @Test void ignoredRepairCostIsStillPreservedByUnlimitedRefund() {
        ItemStack actual = named(null); actual.set(DataComponents.REPAIR_COST, 19);
        SimpleContainer source = new SimpleContainer(2); source.setItem(0, actual.copy());
        ItemAction action = new ItemAction(named(null)); action.requireUndamaged = false;
        var payment = action.take(new NPCShopStorage(), null, new InventoryMultiplexer(source), 1);
        assertTrue(payment.isPossible()); payment.run(); payment.rollback();
        assertTrue(ItemStack.matches(actual, source.getItem(0)));
    }

    @Test void rewardRollbackDoesNotTakeAnUnrelatedLooselyMatchingItem() {
        ItemStack existing = named("Unrelated"); SimpleContainer source = new SimpleContainer(2); source.setItem(0, existing.copy());
        var reward = loose(named(null)).grant(new NPCShopStorage(), null, new InventoryMultiplexer(source), 1);
        assertTrue(reward.isPossible()); reward.run(); reward.rollback();
        assertTrue(ItemStack.matches(existing, source.getItem(0))); assertTrue(source.getItem(1).isEmpty());
        reward.rollback(); assertTrue(ItemStack.matches(existing, source.getItem(0)));
    }

    @Test void limitedRewardTransfersActualStockRatherThanTemplate() {
        ItemStack actual = named("Stock variant"); actual.setDamageValue(4);
        NPCShopStorage stock = limited(actual.copy()); SimpleContainer source = new SimpleContainer(2);
        var reward = loose(named(null)).grant(stock, null, new InventoryMultiplexer(source), 1);
        assertTrue(reward.isPossible()); reward.run();
        assertTrue(ItemStack.matches(actual, source.getItem(0))); assertTrue(stock.getInventory().isEmpty());
        reward.rollback(); assertTrue(source.isEmpty()); assertTrue(ItemStack.matches(actual, stock.getInventory().getFirst()));
    }

    @Test void stockCapacityAccountsForActualComponentVariants() {
        ItemStack first = new ItemStack(Items.EMERALD); first.set(DataComponents.CUSTOM_NAME, Component.literal("Currency"));
        ItemStack second = first.copy(); first.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("A"))));
        second.set(DataComponents.LORE, new ItemLore(List.of(Component.literal("B"))));
        ItemStack template = new ItemStack(Items.EMERALD, 2); template.set(DataComponents.CUSTOM_NAME, Component.literal("Currency"));
        SimpleContainer source = new SimpleContainer(first, second); NPCShopStorage stock = limited(); stock.setInventorySizeLimit(1);
        var payment = loose(template).take(stock, null, new InventoryMultiplexer(source), 1);
        assertFalse(payment.isPossible()); assertThrows(IllegalStateException.class, payment::run);
        assertEquals(1, source.getItem(0).getCount()); assertEquals(1, source.getItem(1).getCount()); assertTrue(stock.getInventory().isEmpty());
    }

    @Test void receiptsArePerExecutionAndIndependentOfLaterConfiguration() {
        ItemStack first = named("First"), second = named("Second");
        SimpleContainer source = new SimpleContainer(3); source.setItem(0, first.copy());
        InventoryMultiplexer inventory = new InventoryMultiplexer(source); NPCShopStorage stock = limited();
        ItemAction action = loose(named(null)); var payment = action.take(stock, null, inventory, 1);
        payment.rollback(); assertTrue(ItemStack.matches(first, source.getItem(0)));
        payment.run(); source.setItem(0, second.copy()); inventory.refresh(); payment.run();
        action.items = List.of(new ItemStack(Items.DIRT)); action.metaFilter = List.of(); action.requireUndamaged = true;
        payment.rollback(); assertTrue(ItemStack.matches(second, source.getItem(0)));
        payment.rollback(); assertTrue(ItemStack.matches(first, source.getItem(1))); assertTrue(stock.getInventory().isEmpty());
        payment.rollback(); assertTrue(source.getItem(2).isEmpty());
    }
}
