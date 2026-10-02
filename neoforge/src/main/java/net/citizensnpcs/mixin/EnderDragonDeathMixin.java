package net.citizensnpcs.mixin;

import java.util.List;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.citizensnpcs.npc.NPCDeaths;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.EnderDragonPart;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragon.class)
public abstract class EnderDragonDeathMixin {
    @WrapOperation(method = "tickDeath", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/Level;globalLevelEvent(ILnet/minecraft/core/BlockPos;I)V"))
    private void citizens$privateDeathSound(Level level, int eventId, BlockPos pos, int data, Operation<Void> original) {
        if (!NPCDeaths.sendVirtualLevelEvent((Entity) (Object) this, eventId, pos, data)) original.call(level, eventId, pos, data);
    }

    @WrapMethod(method = "hurt(Lnet/minecraft/world/entity/boss/EnderDragonPart;Lnet/minecraft/world/damagesource/DamageSource;F)Z")
    private boolean citizens$finishDamage(EnderDragonPart part, DamageSource source, float damage, Operation<Boolean> original) {
        return NPCDeaths.duringDragonDamage((EnderDragon) (Object) this, () -> original.call(part, source, damage));
    }

    @WrapMethod(method = "tickDeath")
    private void citizens$phaseDeath(Operation<Void> original) {
        NPCDeaths.duringDragonDeathTick((EnderDragon) (Object) this, () -> original.call());
    }

    @WrapMethod(method = "kill")
    private void citizens$directKill(Operation<Void> original) {
        NPCDeaths.instantKill((EnderDragon) (Object) this, () -> original.call());
    }

    @Inject(method = "knockBack", at = @At("HEAD"), cancellable = true)
    private void citizens$noCorpseKnockback(ServerLevel level, List<Entity> targets, CallbackInfo callback) {
        if (NPCDeaths.isVisual((Entity) (Object) this)) callback.cancel();
    }

    @Inject(method = "hurt(Ljava/util/List;)V", at = @At("HEAD"), cancellable = true)
    private void citizens$noCorpseAttack(List<Entity> targets, CallbackInfo callback) {
        if (NPCDeaths.isVisual((Entity) (Object) this)) callback.cancel();
    }
}
