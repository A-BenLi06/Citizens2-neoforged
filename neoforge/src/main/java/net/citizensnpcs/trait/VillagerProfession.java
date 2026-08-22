package net.citizensnpcs.trait;

import java.util.Locale;
import java.util.Map;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.npc.VillagerDataHolder;

/**
 * Persists a villager (or zombie villager) NPC's profession.
 * <p>
 * The profession is a registry object in vanilla rather than Bukkit's enum, so the stored value has to be resolved
 * through the registry — the same shape of change {@code MobType} needed for entity types. Bukkit's enum constant names
 * are the registry ids uppercased, so existing saves read back unchanged; the handful of pre-1.14 Bukkit names that no
 * longer exist are mapped explicitly, as upstream does for {@code NORMAL}.
 * <p>
 * Because it is a registry lookup rather than an enum, professions added by other mods work with no code change.
 */
@TraitName("profession")
public class VillagerProfession extends Trait {
    // fully qualified throughout: this class deliberately shares its simple name with upstream's trait, which in turn
    // took it from Bukkit's enum, so the vanilla registry type of the same name cannot be imported
    private net.minecraft.world.entity.npc.VillagerProfession profession = DEFAULT;

    public VillagerProfession() {
        super("profession");
    }

    public net.minecraft.world.entity.npc.VillagerProfession getProfession() {
        return profession;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        net.minecraft.world.entity.npc.VillagerProfession parsed = parse(key.getString(""));
        if (parsed == null)
            throw new NPCLoadException("Invalid profession.");
        profession = parsed;
    }

    /**
     * @return the profession, or null when the name matches nothing
     */
    public static net.minecraft.world.entity.npc.VillagerProfession parse(String raw) {
        if (raw == null || raw.isEmpty())
            return DEFAULT;
        String upper = raw.toUpperCase(Locale.ROOT);
        String replacement = LEGACY_NAMES.get(upper);
        if (replacement != null) {
            upper = replacement;
        }
        ResourceLocation id = ResourceLocation.tryParse(upper.toLowerCase(Locale.ROOT));
        // VILLAGER_PROFESSION is a defaulted registry, so get() answers "none" for a name that does not exist rather
        // than null - the membership check is what actually distinguishes an unknown profession
        return id != null && BuiltInRegistries.VILLAGER_PROFESSION.containsKey(id)
                ? BuiltInRegistries.VILLAGER_PROFESSION.get(id)
                : null;
    }

    @Override
    public void onSpawn() {
        apply();
    }

    /**
     * Re-asserted every tick rather than only on spawn. A villager brain behaviour clears the profession when it cannot
     * find a job site, and the brain still runs whenever the NPC has vanilla AI enabled.
     */
    @Override
    public void run() {
        apply();
    }

    private void apply() {
        if (npc.getEntity() instanceof VillagerDataHolder holder
                && holder.getVillagerData().getProfession() != profession) {
            holder.setVillagerData(holder.getVillagerData().setProfession(profession));
        }
    }


    @Override
    public void save(DataKey key) {
        key.setString("", BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession).getPath().toUpperCase(Locale.ROOT));
    }

    public void setProfession(net.minecraft.world.entity.npc.VillagerProfession profession) {
        this.profession = profession == null ? DEFAULT : profession;
        onSpawn();
    }

    @Override
    public String toString() {
        return "Profession{" + BuiltInRegistries.VILLAGER_PROFESSION.getKey(profession) + "}";
    }

    /**
     * Bukkit profession names that no longer exist. {@code NORMAL} is upstream's own mapping; the rest are the pre-1.14
     * professions Mojang replaced when the trades were reworked.
     */
    private static final net.minecraft.world.entity.npc.VillagerProfession DEFAULT = net.minecraft.world.entity.npc.VillagerProfession.FARMER;
    private static final Map<String, String> LEGACY_NAMES = Map.of("NORMAL", "FARMER", "BLACKSMITH", "ARMORER",
            "PRIEST", "CLERIC", "SMITH", "TOOLSMITH", "HUSK", "FARMER");
}
