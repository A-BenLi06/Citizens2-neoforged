package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.core.NonNullList;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.items.IItemHandler;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.basin.BasinRecipe", remap = false)
public abstract class CreateBasinRecipeEvidenceMixin {
    private static final String APPLY = "apply(Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)Z";
    @WrapMethod(method = APPLY)
    private static boolean citizens$apply(@Coerce Object basin, Recipe<?> recipe, boolean simulate, Operation<Boolean> original) {
        CreateMachineEvidence.beforeBasinRecipe((BlockEntity) basin, recipe, simulate);
        boolean result = original.call(basin, recipe, simulate);
        CreateMachineEvidence.afterBasinRecipe((BlockEntity) basin, simulate, result); return result;
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/basin/BasinRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private static List<ItemStack> citizens$roll(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        var outputs = original.call(recipe, random); CreateMachineEvidence.basinRolled(recipe, outputs); return outputs;
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/basin/BasinRecipe;getRemainingItems(Lnet/minecraft/world/item/crafting/RecipeInput;)Lnet/minecraft/core/NonNullList;"))
    private static NonNullList<ItemStack> citizens$remainders(@Coerce Object recipe, RecipeInput input, Operation<NonNullList<ItemStack>> original) {
        var outputs = original.call(recipe, input); CreateMachineEvidence.basinRemainders(outputs); return outputs;
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Ljava/lang/Math;min(II)I"))
    private static int citizens$fluidPlan(int needed, int available, Operation<Integer> original, @Local FluidStack fluid) {
        int amount = original.call(needed, available); CreateMachineEvidence.basinFluidPlan(fluid, amount); return amount;
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack citizens$consumed(IItemHandler inventory, int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        var result = original.call(inventory, slot, amount, simulate);
        if (!simulate) CreateMachineEvidence.basinItemConsumed(result); return result;
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/fluids/FluidStack;shrink(I)V"))
    private static void citizens$fluidConsumed(FluidStack fluid, int amount, Operation<Void> original) {
        CreateMachineEvidence.basinFluidConsumed(fluid, amount); original.call(fluid, amount);
    }
    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/processing/basin/BasinBlockEntity;acceptOutputs(Ljava/util/List;Ljava/util/List;Z)Z"))
    private static boolean citizens$storage(@Coerce Object basin, List<ItemStack> items, List<FluidStack> fluids, boolean simulate,
            Operation<Boolean> original, @Local(ordinal = 0) int[] itemCounts) {
        boolean accepted = original.call(basin, items, fluids, simulate);
        CreateMachineEvidence.basinAccepted((BlockEntity) basin, items, fluids, simulate, accepted, itemCounts); return accepted;
    }
}
