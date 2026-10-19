package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.press.MechanicalPressBlockEntity", remap = false)
public abstract class CreatePressEvidenceMixin {
    @WrapMethod(method = "tryProcessInWorld")
    private boolean citizens$worldPress(ItemEntity input, boolean simulate, Operation<Boolean> original) {
        return CreateMachineEvidence.duringWorldPress((BlockEntity) (Object) this, input, simulate, () -> original.call(input, simulate));
    }

    @WrapOperation(method = "tryProcessInWorld", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private boolean citizens$singleOutput(Level level, Entity entity, Operation<Boolean> original) {
        boolean accepted = original.call(level, entity); CreateMachineEvidence.worldEntityAdded(entity, accepted); return accepted;
    }
}
