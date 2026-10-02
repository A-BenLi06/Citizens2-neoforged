package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.npc.NPCDeaths;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathMixin {
    @WrapMethod(method = "die")
    private void citizens$ownDeath(DamageSource source, Operation<Void> original) {
        NPCDeaths.duringDeath((ServerPlayer) (Object) this, source, () -> original.call(source));
    }

    @WrapOperation(method = "die", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingDeath(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z"))
    private boolean citizens$acceptedDeath(LivingEntity entity, DamageSource source, Operation<Boolean> original) {
        boolean canceled = original.call(entity, source);
        if (!canceled) NPCDeaths.accepted(entity);
        return canceled;
    }
}
