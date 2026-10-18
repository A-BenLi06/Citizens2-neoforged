package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CraftingEvidence;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Scope native calls without replacing matching, consumption or remainder handling. */
@Mixin(ResultSlot.class)
public abstract class ResultSlotMixin {
    @WrapMethod(method = "onTake")
    private void citizens$observeTake(Player player, ItemStack stack, Operation<Void> original) {
        CraftingEvidence.duringTake((Slot) (Object) this, player, () -> original.call(player, stack));
    }

    @WrapMethod(method = "onQuickCraft(Lnet/minecraft/world/item/ItemStack;I)V")
    private void citizens$observeQuickCraft(ItemStack stack, int amount, Operation<Void> original) {
        CraftingEvidence.duringQuickCraft((Slot) (Object) this, () -> original.call(stack, amount));
    }
}
