package net.citizensnpcs.util;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.npc.NPCRegistries;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;

/**
 * Resolves the ambient/hurt/death sound overrides that {@code /npc sound} stores on an NPC.
 * <p>
 * Upstream applies these by overriding {@code getAmbientSound}/{@code getHurtSound}/{@code getDeathSound} in its own
 * entity subclass for every mob type. This port creates vanilla entities instead, so there is no subclass to override
 * and the three call sites are reached from mixins, which hand the vanilla sound here and use whatever comes back.
 * <p>
 * The stored value is a sound registry path, exactly as upstream stores it, with the empty string meaning "play nothing
 * in this slot". That is distinct from {@link NPC.Metadata#SILENT}, which mutes the entity wholesale (vanilla's own
 * {@code Entity#setSilent}, applied in {@code CitizensNPC.update}) and therefore also takes out step and swim sounds.
 */
public class NPCSounds {
    private NPCSounds() {
    }

    /**
     * @param entity
     *            the entity about to play a sound, which need not be an NPC
     * @param slot
     *            one of {@link NPC.Metadata#AMBIENT_SOUND}, {@link NPC.Metadata#HURT_SOUND},
     *            {@link NPC.Metadata#DEATH_SOUND}
     * @param vanilla
     *            the sound vanilla was about to play, which may already be null
     * @return the override for this slot, null when the override asks for silence, or {@code vanilla} when this is not
     *         an NPC, has no override for the slot, or names a sound this server does not have
     */
    public static SoundEvent resolve(Entity entity, NPC.Metadata slot, SoundEvent vanilla) {
        NPC npc = NPCRegistries.lookup(entity);
        if (npc == null || !npc.data().has(slot))
            return vanilla;
        String path = npc.data().get(slot);
        if (path == null)
            return vanilla;
        if (path.isEmpty())
            return null;
        ResourceLocation id = ResourceLocation.tryParse(path.contains(":") ? path : "minecraft:" + path);
        SoundEvent replacement = id == null ? null : BuiltInRegistries.SOUND_EVENT.get(id);
        // an unknown name leaves the vanilla sound alone rather than silencing the mob, so a typo is audible as
        // "nothing changed" instead of looking like the sound feature is broken
        return replacement != null ? replacement : vanilla;
    }
}
