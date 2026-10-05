package net.citizensnpcs.util;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Presents several containers as one flat array of slots, so a shop can charge across a player's inventory and whatever
 * else it has been given without caring where each item sits.
 * <p>
 * The action edits a detached snapshot, which is written back only after the callback returns successfully. Failed
 * publication attempts restore slot values and verify the result. Sources must expose disjoint slots; arbitrary
 * external side effects are not reversible through this local inventory recovery protocol.
 */
public class InventoryMultiplexer {
    private final ItemStack[] inventory;
    private final List<Container> sources;
    private final int[] sizes;
    private boolean writing;
    private boolean recoveryUncertain;

    private record Write(Container source, int slot, ItemStack before, ItemStack after) { }

    public InventoryMultiplexer(Collection<Container> sources) {
        this.sources = List.copyOf(sources);
        var unique = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Container, Boolean>());
        for (Container source : this.sources) {
            if (!unique.add(source)) throw new IllegalArgumentException("Inventory sources must not repeat a container");
        }
        this.sizes = this.sources.stream().mapToInt(Container::getContainerSize).toArray();
        this.inventory = new ItemStack[java.util.Arrays.stream(sizes).sum()];
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
        recoveryUncertain = false;
    }

    private ItemStack[] readSources() {
        requireLayout();
        ItemStack[] result = new ItemStack[inventory.length];
        int i = 0;
        for (int s = 0; s < sources.size(); s++) {
            Container source = sources.get(s);
            for (int slot = 0; slot < sizes[s]; slot++) {
                result[i++] = source.getItem(slot).copy();
            }
        }
        requireLayout();
        return result;
    }

    private void requireLayout() {
        for (int i = 0; i < sources.size(); i++) {
            if (sources.get(i).getContainerSize() != sizes[i])
                throw new IllegalStateException("Inventory source layout changed");
        }
    }

    public void transact(Consumer<ItemStack[]> action) {
        if (writing) throw new IllegalStateException("Inventory transaction is already in progress");
        if (recoveryUncertain) throw new IllegalStateException("Inventory recovery was incomplete; refresh before writing again");
        writing = true;
        try {
            requireLayout();
            ItemStack[] staged = copy(inventory);
            action.accept(staged);
            ItemStack[] published = copy(staged);
            ItemStack[] before = readSources();
            List<Write> writes = new ArrayList<>(inventory.length);
            int i = 0;
            for (int s = 0; s < sources.size(); s++) {
                for (int slot = 0; slot < sizes[s]; slot++) {
                    writes.add(new Write(sources.get(s), slot, before[i], published[i]));
                    i++;
                }
            }
            int attempted = 0;
            try {
                for (Write write : writes) {
                    attempted++; // A setter may mutate its slot before throwing.
                    write.source().setItem(write.slot(), write.after());
                }
                for (Container source : sources) source.setChanged();
                System.arraycopy(readSources(), 0, inventory, 0, inventory.length);
            } catch (RuntimeException | Error failure) {
                recover(writes, attempted, before, failure);
                throw failure;
            }
        } finally { writing = false; }
    }

    private void recover(List<Write> writes, int attempted, ItemStack[] before, Throwable failure) {
        boolean incomplete = false;
        var touched = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Container, Boolean>());
        for (int i = attempted - 1; i >= 0; i--) {
            Write write = writes.get(i);
            touched.add(write.source());
            try { write.source().setItem(write.slot(), write.before().copy()); }
            catch (RuntimeException | Error restore) {
                incomplete = true;
                failure.addSuppressed(new IllegalStateException("Could not restore inventory slot " + write.slot(), restore));
            }
        }
        for (Container source : sources) {
            if (!touched.remove(source)) continue;
            try { source.setChanged(); }
            catch (RuntimeException | Error restore) {
                incomplete = true;
                failure.addSuppressed(new IllegalStateException("Could not notify restored inventory", restore));
            }
        }
        try {
            ItemStack[] actual = readSources();
            System.arraycopy(actual, 0, inventory, 0, inventory.length);
            for (int i = 0; i < actual.length; i++) {
                if (!ItemStack.matches(actual[i], before[i])) {
                    incomplete = true;
                    failure.addSuppressed(new IllegalStateException("Inventory differs from its pre-write snapshot at slot " + i));
                }
            }
        } catch (RuntimeException | Error read) {
            incomplete = true;
            failure.addSuppressed(new IllegalStateException("Could not read inventory after recovery", read));
        }
        recoveryUncertain = incomplete;
    }

    private static ItemStack[] copy(ItemStack[] source) {
        ItemStack[] result = new ItemStack[source.length];
        for (int i = 0; i < source.length; i++) result[i] = source[i] == null ? ItemStack.EMPTY : source[i].copy();
        return result;
    }
}
