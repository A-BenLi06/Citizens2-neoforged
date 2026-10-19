package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.fluids.spout.SpoutBlockEntity", remap = false)
public abstract class CreateSpoutEvidenceMixin {
    @WrapMethod(method = "whenItemHeld")
    @Coerce private Object citizens$process(@Coerce Object input, @Coerce Object handler, Operation<Object> original) {
        return CreateMachineEvidence.duringFluidProcess((BlockEntity) (Object) this, input, handler, () -> original.call(input, handler));
    }
}
