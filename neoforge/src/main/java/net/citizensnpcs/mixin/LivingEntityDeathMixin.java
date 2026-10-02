package net.citizensnpcs.mixin;

import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.citizensnpcs.npc.NPCDeaths;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;

@Mixin(LivingEntity.class)
public abstract class LivingEntityDeathMixin {
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
