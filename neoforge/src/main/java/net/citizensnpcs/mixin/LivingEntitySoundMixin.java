package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.NPCSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.LivingEntity;

/**
 * Applies an NPC's hurt and death sound overrides.
 * <p>
 * The two sounds are intercepted at their <em>call sites</em> rather than on {@code getHurtSound}/{@code getDeathSound},
 * because 77 vanilla mob classes override those getters and a mixin on the base declaration would never run for any of
 * them. {@code LivingEntity.hurt} and {@code LivingEntity.playHurtSound} are where the choice is actually consumed, and
 * both funnel through {@code makeSound}, which already tolerates a null.
 * <p>
 * {@code hurt} calls {@code makeSound} exactly once — for the death sound — so no ordinal is needed to tell the two
 * apart. {@code Mob} overrides {@code playHurtSound} only to reset its ambient timer and then calls super, so mobs are
 * covered by the {@code LivingEntity} injection.
 *
 * @see MobSoundMixin for the ambient sound, whose call site is on {@code Mob}
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntitySoundMixin {
    @ModifyArg(
            method = "hurt(Lnet/minecraft/world/damagesource/DamageSource;F)Z",
            at = @At(
                    value = "INVOKE",
                    target = "makeSound(Lnet/minecraft/sounds/SoundEvent;)V"))
    private SoundEvent citizens$deathSound(SoundEvent vanilla) {
        return NPCSounds.resolve((LivingEntity) (Object) this, NPC.Metadata.DEATH_SOUND, vanilla);
    }

    @ModifyArg(
            method = "playHurtSound(Lnet/minecraft/world/damagesource/DamageSource;)V",
            at = @At(
                    value = "INVOKE",
                    target = "makeSound(Lnet/minecraft/sounds/SoundEvent;)V"))
    private SoundEvent citizens$hurtSound(SoundEvent vanilla) {
        return NPCSounds.resolve((LivingEntity) (Object) this, NPC.Metadata.HURT_SOUND, vanilla);
    }
}
