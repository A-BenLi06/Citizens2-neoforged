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
import net.neoforged.neoforge.fluids.FluidStack;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.spout.FillingBySpout", remap = false)
public abstract class CreateFillingEvidenceMixin {
    @WrapMethod(method = "fillItem")
    private static ItemStack citizens$fill(Level level, int amount, ItemStack input, FluidStack fluid, Operation<ItemStack> original) {
        CreateMachineEvidence.beforeFilling(input, fluid, amount); return original.call(level, amount, input, fluid);
    }
    @WrapOperation(method = "fillItem", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/fluids/transfer/FillingRecipe;rollResults(Lnet/minecraft/util/RandomSource;)Ljava/util/List;"))
    private static List<ItemStack> citizens$roll(@Coerce Object recipe, RandomSource random, Operation<List<ItemStack>> original) {
        var outputs = original.call(recipe, random); CreateMachineEvidence.fluidRolled(recipe, outputs); return outputs;
    }
}
