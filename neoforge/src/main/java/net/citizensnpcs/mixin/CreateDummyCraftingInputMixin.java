package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.*;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

/** The provider's dummy container owns an expanded inventory, not its empty superclass grid. */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.recipe.DummyCraftingContainer", remap = false)
public abstract class CreateDummyCraftingInputMixin {
    @Shadow @Final private NonNullList<ItemStack> inv;

    public int getWidth() { return inv.size(); }
    public int getHeight() { return inv.isEmpty() ? 0 : 1; }
    public List<ItemStack> getItems() { return inv.stream().map(ItemStack::copy).toList(); }
}
