package net.citizensnpcs.trait.versioned;

import java.util.ArrayList;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.citizensnpcs.api.persistence.MobEffectInstancePersister;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.persistence.Persistable;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.MemoryDataKey;
import net.citizensnpcs.api.util.Messaging;
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
    @Persist(reify = true)
    private StoredEffects persistent = new StoredEffects();
    private final List<MobEffectInstance> temporary = new ArrayList<>();

    public PotionEffectsTrait() {
        super("potioneffects");
    }

    public void addEffect(MobEffectInstance effect) {
        temporary.add(effect);
    }

    public void addPersistentEffect(String name, MobEffectInstance effect) {
        persistent.resolved.put(name, effect);
        if (npc.getEntity() instanceof LivingEntity living) {
            living.addEffect(new MobEffectInstance(effect));
        }
    }

    /** The mutable map of resolved native templates; use hasPersistentEffect/removePersistentEffect for all names. */
    public Map<String, MobEffectInstance> getPersistentEffects() {
        return persistent.resolved;
    }

    /** Unavailable definitions are retained separately because no native effect instance can represent them. */
    public Map<String, String> getUnresolvedEffectTypes() {
        Map<String, String> types = new LinkedHashMap<>();
        persistent.unresolved.forEach((name, raw) -> types.put(name,
                raw instanceof Map<?, ?> data ? Objects.toString(data.get("type"), "") : ""));
        return Collections.unmodifiableMap(types);
    }

    public boolean hasPersistentEffect(String name) {
        return persistent.resolved.containsKey(name) || persistent.unresolved.containsKey(name);
    }

    @Override
    public void onSpawn() {
        if (!(npc.getEntity() instanceof LivingEntity living))
            return;
        for (MobEffectInstance effect : persistent.resolved.values()) {
            // a fresh instance per application: vanilla takes ownership of the one it is handed and counts its duration
            // down, which would drain the stored effect
            living.addEffect(new MobEffectInstance(effect));
        }
    }

    public void removePersistentEffect(String name) {
        persistent.resolved.remove(name);
        persistent.unresolved.remove(name);
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

    /** Uses the original {@code persistent.<literal name>} layout through the normal PersistenceLoader lifecycle. */
    public static final class StoredEffects implements Persistable {
        private static final MobEffectInstancePersister PERSISTER = new MobEffectInstancePersister();
        private final Map<String, Object> unresolved = new LinkedHashMap<>();
        private final Map<String, MobEffectInstance> values = new HashMap<>();
        private final Map<String, MobEffectInstance> resolved = new AbstractMap<>() {
            @Override public Set<Entry<String, MobEffectInstance>> entrySet() { return values.entrySet(); }
            @Override public MobEffectInstance put(String name, MobEffectInstance effect) {
                Objects.requireNonNull(name); Objects.requireNonNull(effect);
                unresolved.remove(name);
                return values.put(name, effect);
            }
        };

        public StoredEffects() { }

        @Override
        public void load(DataKey root) {
            values.clear(); unresolved.clear();
            for (DataKey entry : root.getSubKeys()) {
                MobEffectInstance effect = entry.getRaw("") instanceof Map<?, ?> ? PERSISTER.tryCreate(entry) : null;
                if (effect != null) resolved.put(entry.name(), effect);
                else {
                    unresolved.put(entry.name(), copyData(entry.getRaw("")));
                    Messaging.warn("Preserving unavailable potion effect '" + entry.name() + "' (type '"
                            + entry.getString("type") + "').");
                }
            }
        }

        @Override
        public void save(DataKey root) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            unresolved.forEach((name, raw) -> encoded.put(name, copyData(raw)));
            resolved.forEach((name, effect) -> {
                MemoryDataKey data = new MemoryDataKey();
                PERSISTER.save(effect, data);
                encoded.put(name, data.getValuesDeep());
            });
            // Write the whole map to preserve literal dots in effect names and remove deleted definitions.
            if (encoded.isEmpty()) root.removeKey("");
            else root.setRaw("", encoded);
        }

        private static Object copyData(Object value) {
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copy = new LinkedHashMap<>();
                map.forEach((key, child) -> copy.put(key, copyData(child)));
                return copy;
            }
            if (value instanceof List<?> list) return list.stream().map(StoredEffects::copyData).toList();
            return value;
        }
    }
}
