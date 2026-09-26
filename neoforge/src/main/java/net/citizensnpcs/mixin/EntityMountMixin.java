package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.util.NPCMounts;
import net.minecraft.world.entity.Entity;

/** Revalidates only Citizens-owned requests after all native mount listeners have run. */
@Mixin(Entity.class)
public abstract class EntityMountMixin {
    @WrapOperation(method = { "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", "removeVehicle()V" },
            at = @At(value = "INVOKE", target = "Lnet/neoforged/neoforge/event/EventHooks;canMountEntity(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/entity/Entity;Z)Z"))
    private boolean citizens$validateMount(Entity rider, Entity vehicle, boolean mounting, Operation<Boolean> original) {
        return NPCMounts.dispatch(rider, vehicle, mounting, () -> original.call(rider, vehicle, mounting));
    }

    @WrapOperation(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/Entity;stopRiding()V"))
    private void citizens$scopeDismount(Entity rider, Operation<Void> original, @Local(argsOnly = true) Entity vehicle) {
        NPCMounts.duringMountDismount(rider, vehicle, () -> original.call(rider));
    }

    @Inject(method = "startRiding(Lnet/minecraft/world/entity/Entity;Z)Z", cancellable = true,
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;setPose(Lnet/minecraft/world/entity/Pose;)V"))
    private void citizens$checkDetachment(Entity vehicle, boolean force, CallbackInfoReturnable<Boolean> callback) {
        if (!NPCMounts.canCompleteMount((Entity) (Object) this, vehicle)) callback.setReturnValue(false);
    }
}
