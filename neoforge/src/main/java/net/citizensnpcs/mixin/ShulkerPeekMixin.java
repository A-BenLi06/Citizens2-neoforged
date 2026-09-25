package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;

import net.citizensnpcs.util.ShulkerPeek;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.monster.Shulker;

/** Preserves native armor/events/metadata while allowing scoped quiet requests without changing Entity.silent. */
@Mixin(Shulker.class)
public abstract class ShulkerPeekMixin implements ShulkerPeek.Access {
    @Shadow @Final protected static EntityDataAccessor<Byte> DATA_PEEK_ID;
    @Shadow public abstract void setRawPeekAmount(int amount);
    @Unique private long citizens$peekRevision;
    @Unique private long citizens$quietRevision = -1;

    @Override public int citizens$peek() {
        return ((Shulker) (Object) this).getEntityData().get(DATA_PEEK_ID);
    }

    @Override public long citizens$peekRevision() {
        return citizens$peekRevision;
    }

    @Override public void citizens$peekQuietly(int amount) {
        long previous = citizens$quietRevision;
        citizens$quietRevision = citizens$peekRevision + 1;
        try {
            setRawPeekAmount(amount);
        } finally {
            citizens$quietRevision = previous;
        }
    }

    @Inject(method = "setRawPeekAmount", at = @At("HEAD"))
    private void citizens$request(int amount, CallbackInfo ci, @Share("request") LocalLongRef request,
            @Share("quiet") LocalBooleanRef quiet) {
        request.set(++citizens$peekRevision);
        quiet.set(citizens$quietRevision == citizens$peekRevision);
    }

    @WrapOperation(method = "setRawPeekAmount", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/monster/Shulker;playSound(Lnet/minecraft/sounds/SoundEvent;FF)V"))
    private void citizens$quietSound(Shulker entity, SoundEvent sound, float volume, float pitch, Operation<Void> original,
            @Share("quiet") LocalBooleanRef quiet) {
        if (!quiet.get()) original.call(entity, sound, volume, pitch);
    }

    @WrapOperation(method = "setRawPeekAmount", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/network/syncher/SynchedEntityData;set(Lnet/minecraft/network/syncher/EntityDataAccessor;Ljava/lang/Object;)V"))
    private <T> void citizens$commit(SynchedEntityData data, EntityDataAccessor<T> key, T value, Operation<Void> original,
            @Share("request") LocalLongRef request, @Share("quiet") LocalBooleanRef quiet) {
        // Only our quiet request is conditional. A native event callback may have issued a newer request.
        if (!quiet.get() || request.get() == citizens$peekRevision) original.call(data, key, value);
    }
}
