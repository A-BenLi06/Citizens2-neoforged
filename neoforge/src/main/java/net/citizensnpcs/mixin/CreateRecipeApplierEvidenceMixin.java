package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.Level;

/** Scoped to an admitted machine operation; unrelated callers retain the original RecipeApplier behavior. */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.recipe.RecipeApplier", remap = false)
public abstract class CreateRecipeApplierEvidenceMixin {
    private static final String APPLY = "applyRecipeOn(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/crafting/Recipe;Z)Ljava/util/List;";

    @WrapMethod(method = APPLY)
    private static List<ItemStack> citizens$results(Level level, ItemStack input, Recipe<?> recipe, boolean remainders,
            Operation<List<ItemStack>> original) {
        CreateMachineEvidence.beforeWorldRecipe(input, recipe);
        var outputs = original.call(level, input, recipe, remainders);
        CreateMachineEvidence.worldRecipeResults(outputs); return outputs;
    }

    @WrapOperation(method = APPLY, at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/processing/recipe/ProcessingRecipe;rollResults(Ljava/util/List;Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private static List<ItemStack> citizens$rolled(@Coerce Object recipe, List<?> definitions, RandomSource random,
            Operation<List<ItemStack>> original) {
        var outputs = original.call(recipe, definitions, random); CreateMachineEvidence.batchRolled(recipe, outputs); return outputs;
    }

    @WrapOperation(method = APPLY, at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/ItemStack;getCraftingRemainingItem()Lnet/minecraft/world/item/ItemStack;"))
    private static ItemStack citizens$remainder(ItemStack input, Operation<ItemStack> original) {
        var remainder = original.call(input); CreateMachineEvidence.batchRemainder(remainder); return remainder;
    }

    @WrapOperation(method = "applyRecipeOn(Lnet/minecraft/world/entity/item/ItemEntity;Lnet/minecraft/world/item/crafting/Recipe;Z)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean citizens$bulkOutput(Level level, Entity entity, Operation<Boolean> original) {
        boolean accepted = original.call(level, entity); CreateMachineEvidence.worldEntityAdded(entity, accepted); return accepted;
    }
}
