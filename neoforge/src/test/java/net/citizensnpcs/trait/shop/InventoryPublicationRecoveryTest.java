package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class InventoryPublicationRecoveryTest {
    @Test
    void failedItemChargeRestoresStockAsWellAsPlayerInventory() {
        NPCShopStorage stock = new NPCShopStorage(); stock.setUnlimited(false);
        FaultyContainer player = new FaultyContainer(8);
        InventoryMultiplexer inventory = new InventoryMultiplexer(player);
        var charge = new ItemAction(new ItemStack(Items.EMERALD)).take(stock, null, inventory, 1);
        player.failWrite = true;
        assertThrows(IllegalStateException.class, charge::run);
        assertTrue(stock.getInventory().isEmpty());
        assertEquals(8, player.getItem(0).getCount());
        charge.run();
        assertEquals(1, stock.getInventory().getFirst().getCount());
        assertEquals(7, player.getItem(0).getCount());
        charge.rollback();
        assertTrue(stock.getInventory().isEmpty());
        assertEquals(8, player.getItem(0).getCount());
    }

    @Test
    void failedRewardAndFailedRefundDoNotDuplicateStock() {
        NPCShopStorage stock = new NPCShopStorage(); stock.setUnlimited(false);
        stock.setInventory(new java.util.ArrayList<>(java.util.List.of(new ItemStack(Items.DIAMOND, 3))));
        FaultyContainer player = new FaultyContainer(0);
        InventoryMultiplexer inventory = new InventoryMultiplexer(player);
        var reward = new ItemAction(new ItemStack(Items.DIAMOND)).grant(stock, null, inventory, 1);
        player.failWrite = true;
        assertThrows(IllegalStateException.class, reward::run);
        assertEquals(3, stock.getInventory().getFirst().getCount());
        assertTrue(player.getItem(0).isEmpty());
        reward.run();
        player.failWrite = true;
        assertThrows(IllegalStateException.class, reward::rollback);
        assertEquals(2, stock.getInventory().getFirst().getCount());
        assertEquals(1, player.getItem(0).getCount());
        reward.rollback();
        assertEquals(3, stock.getInventory().getFirst().getCount());
        assertTrue(player.getItem(0).isEmpty());
    }

    @Test
    void setterFailureAfterMutationRestoresAllAttemptedSlotsAndAllowsRetry() {
        SimpleContainer first = new SimpleContainer(new ItemStack(Items.EMERALD, 8));
        FaultyContainer second = new FaultyContainer(9);
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        second.failWrite = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> inventory.transact(stacks -> { stacks[0].setCount(1); stacks[1].setCount(2); }));
        assertSame(second.failure, failure);
        assertEquals(0, failure.getSuppressed().length);
        assertEquals(8, first.getItem(0).getCount());
        assertEquals(9, second.getItem(0).getCount());
        assertEquals(8, inventory.getInventory()[0].getCount());
        inventory.transact(stacks -> stacks[0].shrink(1));
        assertEquals(7, first.getItem(0).getCount());
    }

    @Test
    void notificationFailureAlsoRestoresPublishedSlots() {
        FaultyContainer first = new FaultyContainer(8);
        SimpleContainer second = new SimpleContainer(new ItemStack(Items.DIAMOND, 9));
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        first.failNotification = true;
        assertThrows(IllegalStateException.class,
                () -> inventory.transact(stacks -> { stacks[0].setCount(1); stacks[1].setCount(2); }));
        assertEquals(8, first.getItem(0).getCount());
        assertEquals(9, second.getItem(0).getCount());
    }

    @Test
    void failedRestorationKeepsOriginalErrorContinuesRecoveryAndRequiresRefresh() {
        FaultyContainer first = new FaultyContainer(8);
        FaultyContainer second = new FaultyContainer(9);
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        first.refuseCount = 8; second.failWrite = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> inventory.transact(stacks -> { stacks[0].setCount(1); stacks[1].setCount(2); }));
        assertSame(second.failure, failure);
        assertTrue(failure.getSuppressed().length >= 2);
        assertEquals(1, first.getItem(0).getCount());
        assertEquals(9, second.getItem(0).getCount());
        assertEquals(1, inventory.getInventory()[0].getCount());
        assertThrows(IllegalStateException.class, () -> inventory.transact(stacks -> {}));
        first.refuseCount = -1;
        first.setItem(0, new ItemStack(Items.EMERALD, 8));
        inventory.refresh();
        inventory.transact(stacks -> stacks[0].shrink(1));
        assertEquals(7, first.getItem(0).getCount());
    }

    @Test
    void silentRestoreClampingIsReportedAsIncomplete() {
        FaultyContainer first = new FaultyContainer(8);
        FaultyContainer second = new FaultyContainer(9);
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        first.clampRestore = true; second.failWrite = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> inventory.transact(stacks -> { stacks[0].setCount(1); stacks[1].setCount(2); }));
        assertTrue(failure.getSuppressed().length > 0);
        assertEquals(4, first.getItem(0).getCount());
        assertThrows(IllegalStateException.class, () -> inventory.transact(stacks -> {}));
    }

    @Test
    void equalTotalSizeCannotHideChangedPerContainerLayout() {
        SizedContainer first = new SizedContainer(); SizedContainer second = new SizedContainer();
        assertThrows(IllegalArgumentException.class, () -> new InventoryMultiplexer(first, first));
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        first.visible = 0; second.visible = 2;
        assertThrows(IllegalStateException.class, () -> inventory.transact(stacks -> {}));
        assertEquals(0, first.writes + second.writes);
    }

    private static class FaultyContainer extends SimpleContainer {
        final IllegalStateException failure = new IllegalStateException("expected publication failure");
        boolean failWrite, failNotification, setting, clampRestore;
        int refuseCount = -1;
        FaultyContainer(int count) { super(1); super.setItem(0, new ItemStack(Items.EMERALD, count)); }
        @Override public void setItem(int slot, ItemStack stack) {
            if (stack.getCount() == refuseCount) throw new IllegalStateException("expected restore refusal");
            if (clampRestore && stack.getCount() == 8) stack.setCount(4);
            setting = true;
            try { super.setItem(slot, stack); } finally { setting = false; }
            if (failWrite) { failWrite = false; throw failure; }
        }
        @Override public void setChanged() {
            if (!setting && failNotification) { failNotification = false; throw failure; }
            super.setChanged();
        }
    }
    private static final class SizedContainer extends SimpleContainer {
        int visible = 1, writes;
        SizedContainer() { super(2); }
        @Override public int getContainerSize() { return visible; }
        @Override public void setItem(int slot, ItemStack stack) { writes++; super.setItem(slot, stack); }
    }
}
