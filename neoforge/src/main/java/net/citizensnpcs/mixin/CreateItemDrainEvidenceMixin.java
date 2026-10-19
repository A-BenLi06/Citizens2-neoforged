package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.*;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.drain.ItemDrainBlockEntity", remap = false)
public abstract class CreateItemDrainEvidenceMixin {
    @WrapMethod(method = "continueProcessing")
    private boolean citizens$process(Operation<Boolean> original) {
        return CreateMachineEvidence.duringFluidProcess((BlockEntity) (Object) this, null, null, original::call);
    }
}
