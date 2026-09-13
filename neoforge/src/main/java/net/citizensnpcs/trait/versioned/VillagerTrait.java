package net.citizensnpcs.trait.versioned;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerDataHolder;
import net.minecraft.world.entity.npc.VillagerType;

/**
 * A villager NPC's biome variant and trading level.
 * <p>
 * The variant is a registry object in vanilla rather than Bukkit's {@code Villager.Type} enum, so it is stored by name
 * and resolved through the registry — the same treatment the profession needed, and equally it means variants added by
 * other mods work with no code change. Bukkit's constant names are the registry ids uppercased, so saves read back
 * unchanged.
 * <p>
 * Writes only its own two fields back onto the existing villager data, so this and
 * {@link net.citizensnpcs.trait.VillagerProfession} compose instead of overwriting each other.
 */
@TraitName("villagertrait")
public class VillagerTrait extends Trait {
    @Persist
    private int level = 1;
    private VillagerType type;
    private String unresolvedType;

    public VillagerTrait() {
        super("villagertrait");
    }

    public int getLevel() {
        return level;
    }

    public VillagerType getType() {
        return type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String raw = key.getString("type");
        type = parse(raw);
        unresolvedType = type == null && !raw.isEmpty() ? raw : null;
    }

    @Override
    public void save(DataKey key) {
        key.setString("type", unresolvedType != null ? unresolvedType
                : type == null ? "" : BuiltInRegistries.VILLAGER_TYPE.getKey(type).toString());
    }

    /**
     * @return the variant, or null when the name is empty or matches nothing
     */
    public static VillagerType parse(String raw) {
        if (raw == null || raw.isEmpty())
            return null;
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        // like the profession registry this one is defaulted, so get() answers "plains" for an unknown name - the
        // membership check is what distinguishes an unknown variant
        return id != null && BuiltInRegistries.VILLAGER_TYPE.containsKey(id) ? BuiltInRegistries.VILLAGER_TYPE.get(id)
                : null;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof VillagerDataHolder holder))
            return;
        level = Math.min(5, Math.max(1, level));
        var data = holder.getVillagerData();
        if (type != null && data.getType() != type) {
            data = data.setType(type);
        }
        if (data.getLevel() != level) {
            data = data.setLevel(level);
        }
        if (data != holder.getVillagerData()) {
            holder.setVillagerData(data);
        }
    }

    public void setLevel(int level) {
        this.level = level;
    }

    public void setType(VillagerType type) {
        unresolvedType = null;
        this.type = type;
    }
}
