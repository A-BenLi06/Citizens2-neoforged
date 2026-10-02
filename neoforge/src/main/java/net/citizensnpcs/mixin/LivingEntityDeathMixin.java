package net.citizensnpcs.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.citizensnpcs.npc.NPCDeaths;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import java.util.Collection;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {
    @WrapOperation(method = "dropAllDeathLoot", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/common/CommonHooks;onLivingDrops(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;Ljava/util/Collection;Z)Z"))
    private boolean citizens$collectSpecialDrops(LivingEntity entity, DamageSource source, Collection<ItemEntity> drops,
            boolean recentlyHit, Operation<Boolean> original) {
        // The special native break method still has equipment/stand items to generate. Its owner publishes
        // one combined event at the removal boundary, and takes responsibility for native item admission.
        return NPCDeaths.collectInstantDrops(entity, drops, recentlyHit)
                || original.call(entity, source, drops, recentlyHit);
    }

    @WrapMethod(method = "die")
    private void citizens$ownDeath(DamageSource source, Operation<Void> original) {
        NPCDeaths.duringDeath((LivingEntity) (Object) this, source, () -> original.call(source));
    }

    @Inject(method = "die", at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/LivingEntity;dead:Z",
            opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
    private void citizens$acceptedDeath(DamageSource source, CallbackInfo callback) {
        NPCDeaths.accepted((LivingEntity) (Object) this);
    }
}
