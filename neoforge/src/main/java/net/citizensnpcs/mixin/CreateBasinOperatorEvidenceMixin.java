package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.processing.basin.BasinOperatingBlockEntity", remap = false)
public abstract class CreateBasinOperatorEvidenceMixin {
    @WrapMethod(method = "applyBasinRecipe")
    private void citizens$basinOperation(Operation<Void> original) {
        CreateMachineEvidence.duringProcess((BlockEntity) (Object) this, () -> original.call());
    }
}
