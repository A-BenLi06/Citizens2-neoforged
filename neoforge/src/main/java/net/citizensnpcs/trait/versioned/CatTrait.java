package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.CatVariant;
import net.minecraft.world.item.DyeColor;

/**
 * Cat NPC appearance: breed, collar colour, and the sitting and lying poses.
 * <p>
 * The breed is a registry object in vanilla rather than Bukkit's {@code Cat.Type} enum, so it is stored by name and
 * resolved through the registry — which also means breeds added by other mods work with no code change. Bukkit's enum
 * constant names are the registry ids uppercased, so existing saves read back unchanged.
 */
@TraitName("cattrait")
public class CatTrait extends Trait {
    @Persist
    private DyeColor collarColor = null;
    @Persist
    private boolean lying = false;
    @Persist
    private boolean sitting = false;
    private Holder<CatVariant> type;
    private String unresolvedType;

    public CatTrait() {
        super("cattrait");
    }

    public DyeColor getCollarColor() {
        return collarColor;
    }

    public boolean isLyingDown() {
        return lying;
    }

    public boolean isSitting() {
        return sitting;
    }

    public Holder<CatVariant> getType() {
        return type == null ? defaultVariant() : type;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String raw = key.getString("type");
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        unresolvedType = !raw.isEmpty() && (id == null || !BuiltInRegistries.CAT_VARIANT.containsKey(id)) ? raw : null;
        type = parse(raw);
    }

    @Override
    public void save(DataKey key) {
        key.setString("type", unresolvedType != null ? unresolvedType
                : getType().unwrapKey().map(k -> k.location().toString()).orElse(""));
    }

    public static Holder<CatVariant> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return defaultVariant();
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.CAT_VARIANT.containsKey(id))
            return defaultVariant();
        return BuiltInRegistries.CAT_VARIANT.getHolder(id).map(h -> (Holder<CatVariant>) h).orElseGet(
                CatTrait::defaultVariant);
    }

    private static Holder<CatVariant> defaultVariant() {
        return BuiltInRegistries.CAT_VARIANT.getHolderOrThrow(CatVariant.BLACK);
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof Cat cat))
            return;
        // Bukkit's Sittable#setSitting writes both: the synced pose is what renders, and the ordered-to-sit flag is
        // what vanilla persists and reads back. Writing only the pose would drop the posture on a vanilla reload.
        cat.setOrderedToSit(sitting);
        cat.setInSittingPose(sitting);
        cat.setLying(lying);
        cat.setVariant(getType());
        if (collarColor != null) {
            cat.setCollarColor(collarColor);
        }
    }

    public void setCollarColor(DyeColor color) {
        collarColor = color;
    }

    public void setLyingDown(boolean lying) {
        this.lying = lying;
    }

    public void setSitting(boolean sitting) {
        this.sitting = sitting;
    }

    public void setType(Holder<CatVariant> type) {
        unresolvedType = null;
        this.type = type == null ? defaultVariant() : type;
    }
}
