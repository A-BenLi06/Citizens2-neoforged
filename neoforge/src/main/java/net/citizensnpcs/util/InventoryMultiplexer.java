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
 * The point of {@link #transact} is atomicity: the action sees the whole array, and only once it returns are the changes
 * written back to the real containers. A charge that turns out to be impossible part-way through therefore leaves nothing
 * half-taken.
 */
public class InventoryMultiplexer {
    private final ItemStack[] inventory;
    private final Collection<Container> sources;

    public InventoryMultiplexer(Collection<Container> sources) {
        this.sources = sources;
        this.inventory = new ItemStack[sources.stream().mapToInt(Container::getContainerSize).sum()];
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
        int i = 0;
        for (Container source : sources) {
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                inventory[i++] = source.getItem(slot);
            }
        }
    }

    public void transact(Consumer<ItemStack[]> action) {
        action.accept(inventory);
        int i = 0;
        for (Container source : sources) {
            for (int slot = 0; slot < source.getContainerSize(); slot++) {
                ItemStack stack = inventory[i++];
                source.setItem(slot, stack == null ? ItemStack.EMPTY : stack);
            }
        }
        for (Container source : sources) {
            source.setChanged();
        }
    }
}
