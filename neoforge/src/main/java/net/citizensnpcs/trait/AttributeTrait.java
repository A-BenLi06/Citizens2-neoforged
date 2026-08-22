package net.citizensnpcs.trait;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;

import java.util.HashMap;
import java.util.Map;

/**
 * Base values for an NPC's entity attributes — max health, movement speed, follow range and the rest.
 * <p>
 * Bukkit's attribute enum names are the registry ids with their dots turned into underscores and uppercased
 * ({@code GENERIC_MAX_HEALTH} for {@code generic.max_health}), so that transformation is what stored names are matched
 * against. Doing it by rule rather than by table means attributes added by other mods work, and there is no list to keep
 * in step with Mojang's renames. A full registry id is accepted too.
 */
@TraitName("attributetrait")
public class AttributeTrait extends Trait {
    private final Map<Holder<Attribute>, Double> attributes = new HashMap<>();

    public AttributeTrait() {
        super("attributetrait");
    }

    public Double getAttributeValue(Holder<Attribute> attribute) {
        return attributes.get(attribute);
    }

    public boolean hasAttribute(Holder<Attribute> attribute) {
        return attributes.containsKey(attribute);
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        attributes.clear();
        for (DataKey sub : key.getRelative("attributes").getSubKeys()) {
            Holder<Attribute> attribute = parse(sub.name());
            if (attribute == null) {
                // named but unresolvable: say which one rather than dropping it silently
                Messaging.warn("Unknown attribute '" + sub.name() + "' on NPC", npc, "- ignoring it.");
                continue;
            }
            attributes.put(attribute, sub.getDouble(""));
        }
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("attributes");
        for (Map.Entry<Holder<Attribute>, Double> entry : attributes.entrySet()) {
            entry.getKey().unwrapKey().ifPresent(
                    k -> key.setDouble("attributes." + bukkitNameOf(k.location()), entry.getValue()));
        }
    }

    @Override
    public void onSpawn() {
        if (!(npc.getEntity() instanceof LivingEntity living))
            return;
        for (Map.Entry<Holder<Attribute>, Double> entry : attributes.entrySet()) {
            AttributeInstance instance = living.getAttribute(entry.getKey());
            if (instance != null) {
                instance.setBaseValue(entry.getValue());
            }
        }
    }

    public void resetToDefaultValue(Holder<Attribute> attribute) {
        attributes.remove(attribute);
        if (!(npc.getEntity() instanceof LivingEntity living))
            return;
        AttributeInstance instance = living.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(instance.getAttribute().value().getDefaultValue());
        }
    }

    public void setAttributeValue(Holder<Attribute> attribute, double value) {
        attributes.put(attribute, value);
        if (npc.getEntity() instanceof LivingEntity living) {
            AttributeInstance instance = living.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(value);
            }
        }
    }

    /**
     * @return the attribute, or null when the name matches neither a Bukkit-style name nor a registry id
     */
    @SuppressWarnings("unchecked")
    public static Holder<Attribute> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        String upper = raw.toUpperCase(Locale.ROOT);
        for (ResourceLocation id : BuiltInRegistries.ATTRIBUTE.keySet()) {
            if (bukkitNameOf(id).equals(upper))
                return BuiltInRegistries.ATTRIBUTE.getHolder(id).map(h -> (Holder<Attribute>) h).orElse(null);
        }
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        return id == null ? null
                : BuiltInRegistries.ATTRIBUTE.getHolder(id).map(h -> (Holder<Attribute>) h).orElse(null);
    }

    /** The name Bukkit's attribute enum would use for this registry id. */
    private static String bukkitNameOf(ResourceLocation id) {
        return id.getPath().replace('.', '_').toUpperCase(Locale.ROOT);
    }
}
