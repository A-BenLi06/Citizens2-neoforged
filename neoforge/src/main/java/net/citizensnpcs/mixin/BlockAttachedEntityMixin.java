package net.citizensnpcs.mixin;

import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.decoration.BlockAttachedEntity;
import net.minecraft.world.entity.decoration.HangingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Citizens ticks hanging NPCs through its registry, without vanilla's periodic support-removal tick. */
@Mixin(BlockAttachedEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Inject(method = "tick()V", at = @At("HEAD"), cancellable = true)
    private void citizens$hangingNpcTick(CallbackInfo callback) {
        if ((Object) this instanceof HangingEntity hanging && !hanging.level().isClientSide
                && NPCRegistries.lookup(hanging) != null) callback.cancel();
    }
}
