package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional pinned native millstone call boundaries, preserving provider matching, consumption and RNG. */
@Pseudo
@Mixin(targets="com.simibubi.create.content.kinetics.millstone.MillstoneBlockEntity", remap=false)
public abstract class CreateMillstoneEvidenceMixin {
    @WrapMethod(method="process")
    private void citizens$process(Operation<Void> original) {
        CreateMachineEvidence.duringProcess((BlockEntity)(Object)this, () -> original.call());
    }
    @WrapOperation(method="process", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/ItemStack;shrink(I)V"))
    private void citizens$input(ItemStack input, int amount, Operation<Void> original) {
        CreateMachineEvidence.beforeConsume(input, amount); original.call(input, amount);
    }
    @WrapOperation(method="process", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/ItemStack;getCraftingRemainingItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack citizens$remainder(ItemStack input, Operation<ItemStack> original) {
        var remainder = original.call(input); CreateMachineEvidence.remainder(remainder); return remainder;
    }
    @WrapOperation(method="process", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/kinetics/millstone/MillingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<ItemStack> citizens$rolled(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        var results = original.call(recipe, random); CreateMachineEvidence.rolled(recipe, results); return results;
    }
    @Inject(method="process", at=@At("RETURN"))
    private void citizens$stored(CallbackInfo ci) { CreateMachineEvidence.afterInsertion(); }
}
