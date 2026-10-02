package net.citizensnpcs.mixin;

import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.world.entity.OminousItemSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A Citizens visual is persistent; its configured item is not a scheduled vanilla reward. */
@Mixin(OminousItemSpawner.class)
public abstract class OminousItemSpawnerNPCMixin {
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void citizens$visualTick(CallbackInfo callback) {
        OminousItemSpawner entity = (OminousItemSpawner) (Object) this;
        if (!entity.level().isClientSide && NPCRegistries.lookup(entity) != null) callback.cancel();
    }
}
