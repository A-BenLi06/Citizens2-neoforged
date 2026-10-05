package net.citizensnpcs.util;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Presents several containers as one flat array of slots, so a shop can charge across a player's inventory and whatever
 * else it has been given without caring where each item sits.
 * <p>
 * The action edits a detached snapshot, which is written back only after the callback returns successfully. Direct
 * external writes and arbitrary container side effects during publication are outside this callback isolation boundary.
 */
public class InventoryMultiplexer {
    private final ItemStack[] inventory;
    private final Collection<Container> sources;
    private boolean writing;

    public InventoryMultiplexer(Collection<Container> sources) {
        this.sources = List.copyOf(sources);
        this.inventory = new ItemStack[this.sources.stream().mapToInt(Container::getContainerSize).sum()];
        refresh();
    }

    public InventoryMultiplexer(Container... containers) {
        this(List.of(containers));
    }

    public ItemStack[] getInventory() {
        return inventory;
    }

    /** Re-reads the real containers, discarding anything staged but not written. */
    public void refresh() {
        if (writing) throw new IllegalStateException("Inventory transaction is already in progress");
        System.arraycopy(readSources(), 0, inventory, 0, inventory.length);
    }

    private ItemStack[] readSources() {
        requireLayout();
        ItemStack[] result = new ItemStack[inventory.length];
        int i = 0;
        for (Container source : sources) {
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                result[i++] = source.getItem(slot).copy();
            }
        }
        return result;
    }

    private void requireLayout() {
        if (sources.stream().mapToInt(Container::getContainerSize).sum() != inventory.length)
            throw new IllegalStateException("Inventory source layout changed");
    }

    public void transact(Consumer<ItemStack[]> action) {
        if (writing) throw new IllegalStateException("Inventory transaction is already in progress");
        writing = true;
        try {
            ItemStack[] staged = copy(inventory);
            action.accept(staged);
            ItemStack[] published = copy(staged);
            requireLayout();
            int i = 0;
            for (Container source : sources) {
                for (int slot = 0; slot < source.getContainerSize(); slot++) {
                    source.setItem(slot, published[i++].copy());
                }
            }
            for (Container source : sources) {
                source.setChanged();
            }
            System.arraycopy(readSources(), 0, inventory, 0, inventory.length);
        } finally { writing = false; }
    }

    private static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? ItemStack.EMPTY : source[i].copy();
        return result;
    }
}
