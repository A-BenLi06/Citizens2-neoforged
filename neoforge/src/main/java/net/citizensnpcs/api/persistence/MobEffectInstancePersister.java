package net.citizensnpcs.api.persistence;

import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;

/**
 * Replaces upstream's {@code PotionEffectPersister}. The on-disk layout is unchanged, including the key names — Bukkit's
 * potion effect type keys are the same registry ids vanilla uses, so existing saves read back as they are.
 * <p>
 * Bukkit calls the flag that draws the swirls "particles" and vanilla calls it "visible"; the disk key stays
 * {@code particles}.
 */
public class MobEffectInstancePersister implements Persister<MobEffectInstance> {
    @Override
    public MobEffectInstance create(DataKey root) {
        if (!root.keyExists())
            return null;
        String raw = root.getString("type");
        ResourceLocation id = raw == null || raw.isEmpty() ? null : ResourceLocation.tryParse(raw);
        Holder<MobEffect> effect = id == null ? null
                : BuiltInRegistries.MOB_EFFECT.getHolder(id).map(h -> (Holder<MobEffect>) h).orElse(null);
        if (effect == null) {
            Messaging.warn("Unknown potion effect type '" + raw + "', dropping the effect.");
            return null;
        }
        return new MobEffectInstance(effect, root.getInt("duration"), root.getInt("amplifier"),
                root.getBoolean("ambient"), root.getBoolean("particles"), root.getBoolean("icon"));
    }

    @Override
    public void save(MobEffectInstance instance, DataKey root) {
        root.setString("type", instance.getEffect().unwrapKey().map(k -> k.location().toString()).orElse(""));
        root.setInt("amplifier", instance.getAmplifier());
        root.setInt("duration", instance.getDuration());
        root.setBoolean("particles", instance.isVisible());
        root.setBoolean("ambient", instance.isAmbient());
        root.setBoolean("icon", instance.showIcon());
    }
}
