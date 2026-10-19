package net.citizensnpcs.util;

import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/** Read-only pinned basin storage, including native pending spout outputs. */
public interface CreateBasinStorage {
    Snapshot citizens$basinSnapshot();
    record Snapshot(List<ItemStack> items, List<FluidStack> fluids) {
        public Snapshot {
            items = items.stream().map(ItemStack::copy).toList();
            fluids = fluids.stream().map(FluidStack::copy).toList();
        }
        @Override public List<ItemStack> items() { return items.stream().map(ItemStack::copy).toList(); }
        @Override public List<FluidStack> fluids() { return fluids.stream().map(FluidStack::copy).toList(); }
    }
}
