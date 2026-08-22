package net.citizensnpcs.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.util.NPCSounds;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Mob;

/**
 * Applies an NPC's ambient sound override, at the one call site that consumes {@code getAmbientSound}.
 * <p>
 * Four of the five vanilla classes that override {@code playAmbientSound} (axolotl, fox, piglin, shulker) call
 * {@code super.playAmbientSound()} once their own condition passes, so they are covered here too. The exceptions are a
 * fox mid-screech and a breeze, both of which reach for {@code playSound}/{@code playLocalSound} directly and so keep
 * their vanilla sound; neither is worth a second injection.
 *
 * @see LivingEntitySoundMixin for the hurt and death sounds
 */
@Mixin(Mob.class)
public abstract class MobSoundMixin {
    @ModifyArg(
            method = "playAmbientSound()V",
            at = @At(
                    value = "INVOKE",
                    target = "makeSound(Lnet/minecraft/sounds/SoundEvent;)V"))
    private SoundEvent citizens$ambientSound(SoundEvent vanilla) {
        return NPCSounds.resolve((Mob) (Object) this, NPC.Metadata.AMBIENT_SOUND, vanilla);
    }
}
