package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.world.entity.animal.Rabbit;

/**
 * Persists a rabbit NPC's fur variant.
 * <p>
 * Three of the seven names differ between Bukkit's {@code Rabbit.Type} and vanilla's {@code Rabbit.Variant}, so saved
 * values are mapped rather than parsed straight — the same treatment {@code MobType} gives entity type names.
 */
@TraitName("rabbittype")
public class RabbitType extends Trait {
    private Rabbit.Variant type = Rabbit.Variant.BROWN;

    public RabbitType() {
        super("rabbittype");
    }

    public Rabbit.Variant getRabbitType() {
        return type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        type = parse(key.keyExists("type") ? key.getString("type") : key.getString(""));
    }

    @Override
    public void save(DataKey key) {
        key.removeKey("");
        key.setString("type", type.name());
    }

    public static Rabbit.Variant parse(String raw) {
        if (raw == null || raw.isEmpty())
            return Rabbit.Variant.BROWN;
        String upper = raw.toUpperCase(Locale.ROOT);
        Rabbit.Variant legacy = LEGACY_NAMES.get(upper);
        if (legacy != null)
            return legacy;
        try {
            return Rabbit.Variant.valueOf(upper);
        } catch (IllegalArgumentException ex) {
            return Rabbit.Variant.BROWN;
        }
    }

    @Override
    public void onSpawn() {
        if (npc.getCosmeticEntity() instanceof Rabbit rabbit) {
            rabbit.setVariant(type);
        }
    }

    public void setType(Rabbit.Variant type) {
        this.type = type;
        onSpawn();
    }

    @Override
    public String toString() {
        return "RabbitType{" + type.name() + "}";
    }

    /** Bukkit's spelling for the three variants vanilla names differently. */
    private static final Map<String, Rabbit.Variant> LEGACY_NAMES = Map.of("BLACK_AND_WHITE",
            Rabbit.Variant.WHITE_SPLOTCHED, "SALT_AND_PEPPER", Rabbit.Variant.SALT, "THE_KILLER_BUNNY",
            Rabbit.Variant.EVIL);
}
