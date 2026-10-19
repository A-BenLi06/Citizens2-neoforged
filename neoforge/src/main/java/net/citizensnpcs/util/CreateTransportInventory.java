package net.citizensnpcs.util;

import java.util.List;
import net.minecraft.world.item.ItemStack;

/** Read-only bridge implemented by pinned optional provider mixins. */
public interface CreateTransportInventory {
    List<ItemStack> citizens$transportSnapshot();
}
