package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.sequenced.SequencedAssemblyRecipe", remap = false)
public abstract class CreateAssemblyEvidenceMixin {
    @WrapMethod(method = "advance")
    private ItemStack citizens$advance(ResourceLocation id, ItemStack input, RandomSource random, Operation<ItemStack> original) {
        return CreateMachineEvidence.duringAssemblyAdvance(this, id, input, () -> original.call(id, input, random));
    }
}
