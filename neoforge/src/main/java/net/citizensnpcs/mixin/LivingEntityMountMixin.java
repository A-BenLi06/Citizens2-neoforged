package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.NPCMounts;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public abstract class LivingEntityMountMixin {
    @WrapOperation(method = "stopRiding", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/LivingEntity;dismountVehicle(Lnet/minecraft/world/entity/Entity;)V"))
    private void citizens$preserveNewVehicle(LivingEntity rider, Entity oldVehicle, Operation<Void> original) {
        if (NPCMounts.shouldPlaceAfterDismount(rider, oldVehicle)) original.call(rider, oldVehicle);
    }
}
