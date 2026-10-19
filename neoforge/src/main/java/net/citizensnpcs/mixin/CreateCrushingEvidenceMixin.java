package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets="com.simibubi.create.content.kinetics.crusher.CrushingWheelControllerBlockEntity", remap=false)
public abstract class CreateCrushingEvidenceMixin {
    @WrapMethod(method="applyRecipe") private void citizens$process(Operation<Void> original) {
        CreateMachineEvidence.duringProcess((BlockEntity)(Object)this, () -> original.call());
    }
    @Inject(method="applyRecipe", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/processing/recipe/ProcessingInventory;clear()V"))
    private void citizens$input(CallbackInfo ci) { CreateMachineEvidence.beforeBatchClear(); }
    @WrapOperation(method="applyRecipe", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/processing/recipe/StandardProcessingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private List<ItemStack> citizens$rolled(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        var results=original.call(recipe,random); CreateMachineEvidence.batchRolled(recipe,results); return results;
    }
    @WrapOperation(method="applyRecipe", at=@At(value="INVOKE", target="Lnet/minecraft/world/item/ItemStack;getCraftingRemainingItem()Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack citizens$remainder(ItemStack input, Operation<ItemStack> original) {
        var remainder=original.call(input); CreateMachineEvidence.batchRemainder(remainder); return remainder;
    }
    @Inject(method="applyRecipe", at=@At(value="INVOKE", target="Lcom/simibubi/create/content/processing/recipe/ProcessingInventory;setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V"))
    private void citizens$store(CallbackInfo ci) { CreateMachineEvidence.beforeBatchStorage(); }
    @Inject(method="applyRecipe", at=@At("RETURN")) private void citizens$stored(CallbackInfo ci) { CreateMachineEvidence.afterBatchStorage(); }
}
