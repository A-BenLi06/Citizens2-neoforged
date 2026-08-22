package net.citizensnpcs.api.trait.trait;

import java.util.Locale;

import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.citizensnpcs.api.util.Messaging;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

/**
 * Represents an NPC's mob type.
 * <p>
 * <b>Storage format.</b> Bukkit's {@code EntityType} is an enum, so upstream stores its constant name
 * ({@code ZOMBIE}). Minecraft's is a registry object, so this writes the registry id ({@code minecraft:zombie})
 * instead. Loading accepts both: an id is parsed directly, and anything else is treated as a legacy enum name, which
 * is lowercased and mapped through {@link #LEGACY_NAMES} for the handful of constants Bukkit and Minecraft spell
 * differently. An unrecognised value falls back to {@code PLAYER}, as upstream does.
 */
@TraitName("type")
public class MobType extends Trait {
    private EntityType<?> type = EntityType.PLAYER;

    public MobType() {
        super("type");
    }

    /**
     * Gets the type of mob that an NPC is.
     *
     * @return The mob type
     */
    public EntityType<?> getType() {
        return type;
    }

    @Override
    public void load(DataKey key) {
        String raw = key.getString("");
        type = parse(raw);
        if (type == null) {
            if (raw != null && !raw.isEmpty()) {
                Messaging.warn("Unknown NPC mob type '" + raw + "', defaulting to PLAYER");
            }
            type = EntityType.PLAYER;
        }
        npc.setEntityType(type);
    }

    @Override
    public void onSpawn() {
        type = npc.getEntity().getType();
    }

    @Override
    public void save(DataKey key) {
        key.setString("", EntityType.getKey(type).toString());
    }

    /**
     * Sets the type of mob that an NPC is.
     *
     * @param type
     *            Mob type to set the NPC as
     */
    public void setType(EntityType<?> type) {
        this.type = type;
    }

    @Override
    public String toString() {
        return "MobType{" + EntityType.getKey(type) + "}";
    }

    /**
     * @return the entity type for a registry id or a legacy Bukkit enum name, or null if neither matches
     */
    public static EntityType<?> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        String normalised = raw.toLowerCase(Locale.ROOT);
        normalised = LEGACY_NAMES.getOrDefault(normalised, normalised);
        ResourceLocation id = ResourceLocation.read(normalised).result().orElse(null);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    /**
     * Like {@link #parse}, but also accepts the loose spellings a user might type at a command prompt: separators may
     * be omitted or written as {@code -}/space, and a unique prefix is enough.
     * <p>
     * Upstream does this in {@code SimpleNPCDataStore.matchEntityType} against the Bukkit enum; keeping it means
     * {@code /npc create --type zomb} and saves holding {@code Zombie Villager} still resolve.
     *
     * @return the matched type, or null if nothing matched or a prefix was ambiguous
     */
    public static EntityType<?> match(String raw) {
        EntityType<?> exact = parse(raw);
        if (exact != null)
            return exact;
        if (raw == null || raw.isEmpty())
            return null;

        String wanted = raw.toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        String collapsed = wanted.replace("_", "");
        EntityType<?> prefixMatch = null;
        boolean ambiguous = false;
        for (EntityType<?> candidate : BuiltInRegistries.ENTITY_TYPE) {
            String path = EntityType.getKey(candidate).getPath();
            if (path.equals(wanted) || path.replace("_", "").equals(collapsed))
                return candidate;
            if (path.startsWith(wanted)) {
                ambiguous |= prefixMatch != null;
                prefixMatch = candidate;
            }
        }
        return ambiguous ? null : prefixMatch;
    }

    /**
     * Bukkit enum names whose lowercased form is not the Minecraft registry path. {@code PIG_ZOMBIE} is upstream's own
     * special case; the rest are Bukkit spellings that never matched vanilla.
     */
    private static final java.util.Map<String, String> LEGACY_NAMES = java.util.Map.of("pig_zombie",
            "zombified_piglin", "dropped_item", "item", "primed_tnt", "tnt", "leash_hitch", "leash_knot",
            "mushroom_cow", "mooshroom", "snowman", "snow_golem", "ender_crystal", "end_crystal", "fishing_hook",
            "fishing_bobber", "splash_potion", "potion");
}
