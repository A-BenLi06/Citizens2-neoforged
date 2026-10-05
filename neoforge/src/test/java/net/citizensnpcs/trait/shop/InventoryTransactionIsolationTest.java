package net.citizensnpcs.trait.shop;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

class InventoryTransactionIsolationTest {
    @Test
    void publishedSnapshotReflectsNativeContainerStackLimits() {
        SimpleContainer source = new SimpleContainer(1);
        InventoryMultiplexer inventory = new InventoryMultiplexer(source);
        inventory.transact(stacks -> stacks[0] = new ItemStack(Items.EMERALD, 100));
        assertEquals(64, source.getItem(0).getCount());
        assertEquals(64, inventory.getInventory()[0].getCount());
    }
    @Test
    void failedCallbackCannotMutateAnyRealContainerAndCanRetry() {
        SimpleContainer first = new SimpleContainer(new ItemStack(Items.EMERALD, 8));
        SimpleContainer second = new SimpleContainer(new ItemStack(Items.DIAMOND, 3));
        InventoryMultiplexer inventory = new InventoryMultiplexer(first, second);
        assertThrows(IllegalStateException.class, () -> inventory.transact(stacks -> {
            stacks[0].shrink(4); stacks[1] = ItemStack.EMPTY;
            throw new IllegalStateException("expected callback failure");
        }));
        assertEquals(8, first.getItem(0).getCount());
        assertEquals(3, second.getItem(0).getCount());
        assertEquals(8, inventory.getInventory()[0].getCount());
        inventory.transact(stacks -> stacks[0].shrink(2));
        assertEquals(6, first.getItem(0).getCount());
    }

    @Test
    void retainedCallbackAndReadSnapshotsCannotChangePublishedInventory() {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.EMERALD, 8));
        InventoryMultiplexer inventory = new InventoryMultiplexer(source);
        inventory.getInventory()[0].shrink(1);
        assertEquals(8, source.getItem(0).getCount());
        inventory.refresh();
        ItemStack[][] retained = { null };
        inventory.transact(stacks -> { stacks[0].shrink(2); retained[0] = stacks; });
        retained[0][0].shrink(5);
        assertEquals(6, source.getItem(0).getCount());
        assertEquals(6, inventory.getInventory()[0].getCount());
        source.getItem(0).shrink(1);
        assertEquals(6, inventory.getInventory()[0].getCount());
        inventory.refresh();
        assertEquals(5, inventory.getInventory()[0].getCount());
    }

    @Test
    void reentrantTransactionsRejectBeforePublishingAndReleaseGuard() {
        SimpleContainer source = new SimpleContainer(new ItemStack(Items.EMERALD, 8));
        InventoryMultiplexer inventory = new InventoryMultiplexer(source);
        assertThrows(IllegalStateException.class, () -> inventory.transact(stacks -> {
            stacks[0].shrink(2); inventory.transact(inner -> inner[0].shrink(1));
        }));
        assertEquals(8, source.getItem(0).getCount());
        inventory.transact(stacks -> stacks[0].shrink(1));
        assertEquals(7, source.getItem(0).getCount());
    }

    @Test
    void privateStockCallbackRollbackAndRetainedAliasesAreIsolated() {
        NPCShopStorage storage = new NPCShopStorage(); storage.setUnlimited(false);
        storage.setInventory(new ArrayList<>(List.of(new ItemStack(Items.DIAMOND, 10))));
        assertThrows(IllegalStateException.class, () -> storage.transact(stacks -> {
            stacks[0].shrink(4); throw new IllegalStateException("expected stock failure");
        }));
        assertEquals(10, storage.getInventory().getFirst().getCount());
        ItemStack[][] retained = { null };
        storage.transact(stacks -> { stacks[0].shrink(3); retained[0] = stacks; }, 1);
        retained[0][0].shrink(6);
        assertEquals(7, storage.getInventory().getFirst().getCount());
        assertThrows(IllegalStateException.class, () -> storage.transact(stacks -> storage.transact(inner -> {})));
        assertThrows(IllegalStateException.class, () -> storage.transact(stacks -> storage.setInventory(new ArrayList<>())));
        assertEquals(7, storage.getInventory().getFirst().getCount());
    }
}
