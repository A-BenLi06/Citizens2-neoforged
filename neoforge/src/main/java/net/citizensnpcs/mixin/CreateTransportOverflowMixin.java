package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.CreateMachineEvidence;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

@Mixin(Containers.class)
public abstract class CreateTransportOverflowMixin {
    @WrapOperation(method = "dropItemStack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean citizens$overflow(Level level, Entity entity, Operation<Boolean> original) {
        boolean accepted = original.call(level, entity); CreateMachineEvidence.transportEntityAdded(entity, accepted); return accepted;
    }
}
