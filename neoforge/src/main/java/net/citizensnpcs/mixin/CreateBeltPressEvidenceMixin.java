package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.util.CreateMachineEvidence;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.press.BeltPressingCallbacks", remap = false)
public abstract class CreateBeltPressEvidenceMixin {
    @WrapMethod(method = "whenItemHeld")
    @Coerce
    private static Object citizens$transportPress(@Coerce Object input, @Coerce Object handler, @Coerce Object behaviour,
            Operation<Object> original) {
        return CreateMachineEvidence.duringTransportPress(input, handler, behaviour, () -> original.call(input, handler, behaviour));
    }
}
