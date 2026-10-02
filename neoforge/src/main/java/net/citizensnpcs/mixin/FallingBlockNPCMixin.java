package net.citizensnpcs.mixin;

import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Citizens drives the NPC tick; falling-block visuals move without placing blocks or dropping themselves. */
@Mixin(FallingBlockEntity.class)
public abstract class FallingBlockNPCMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void citizens$visualTick(CallbackInfo callback) {
        FallingBlockEntity entity = (FallingBlockEntity) (Object) this;
        if (entity.level().isClientSide || NPCRegistries.lookup(entity) == null) return;
        Vec3 motion = entity.getDeltaMovement();
        if (Math.abs(motion.x) > 0.001 || Math.abs(motion.y) > 0.001 || Math.abs(motion.z) > 0.001) {
            motion = motion.scale(0.98);
            entity.setDeltaMovement(motion);
            entity.move(MoverType.SELF, motion);
        }
        callback.cancel();
    }
}
