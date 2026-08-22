package net.citizensnpcs.trait.versioned;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * Potion effects on an NPC — named ones that survive a restart, and unnamed ones that last only for this session.
 * <p>
 * Persisted through {@link net.citizensnpcs.api.persistence.MobEffectInstancePersister}, whose on-disk layout matches
 * upstream's, so existing saves read back unchanged.
 */
@TraitName("potioneffects")
public class PotionEffectsTrait extends Trait {
    @Persist(valueType = MobEffectInstance.class)
    private final Map<String, MobEffectInstance> persistent = new HashMap<>();
    private final List<MobEffectInstance> temporary = new ArrayList<>();

    public PotionEffectsTrait() {
        super("potioneffects");
    }

    public void addEffect(MobEffectInstance effect) {
        temporary.add(effect);
    }

    public void addPersistentEffect(String name, MobEffectInstance effect) {
        persistent.put(name, effect);
        if (npc.getEntity() instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(effect));
        }
    }

    public Map<String, MobEffectInstance> getPersistentEffects() {
        return persistent;
    }

    @Override
    public void onSpawn() {
        if (!(npc.getEntity() instanceof LivingEntity living))
            return;
        for (MobEffectInstance effect : persistent.values()) {
            // a fresh instance per application: vanilla takes ownership of the one it is handed and counts its duration
            // down, which would drain the stored effect
            living.addEffect(new MobEffectInstance(effect));
        }
    }

    public void removePersistentEffect(String name) {
        persistent.remove(name);
    }

    @Override
    public void run() {
        if (temporary.isEmpty() || !(npc.getEntity() instanceof LivingEntity living))
            return;
        for (MobEffectInstance effect : temporary) {
            living.addEffect(new MobEffectInstance(effect));
        }
        temporary.clear();
    }
}
