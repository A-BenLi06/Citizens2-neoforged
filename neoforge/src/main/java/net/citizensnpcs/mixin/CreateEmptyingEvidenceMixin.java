package net.citizensnpcs.mixin;

import java.util.List;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.transfer.GenericItemEmptying", remap = false)
public abstract class CreateEmptyingEvidenceMixin {
    @WrapMethod(method = "emptyItem")
    @Coerce private static Object citizens$empty(Level level, ItemStack input, boolean simulate, Operation<Object> original) {
        CreateMachineEvidence.beforeEmptying(input, simulate);
        var result = original.call(level, input, simulate); CreateMachineEvidence.afterEmptying(input, simulate, result); return result;
    }
    @WrapOperation(method = "emptyItem", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/EmptyingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private static List<ItemStack> citizens$roll(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        var outputs = original.call(recipe, random); CreateMachineEvidence.fluidRolled(recipe, outputs); return outputs;
    }
}
