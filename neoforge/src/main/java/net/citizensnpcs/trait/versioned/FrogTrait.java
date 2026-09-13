package net.citizensnpcs.trait.versioned;

import java.util.Locale;

import net.citizensnpcs.api.exception.NPCLoadException;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.citizensnpcs.api.util.DataKey;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.FrogVariant;
import net.minecraft.world.entity.animal.frog.Frog;

/**
 * A frog NPC variant (temperate / warm / cold).
 * <p>
 * A registry object in vanilla rather than Bukkit's enum, so stored by name and resolved through the registry; variants
 * added by other mods therefore work unchanged. The three vanilla names match Bukkit's constants.
 */
@TraitName("frogtrait")
public class FrogTrait extends Trait {
    private Holder<FrogVariant> variant;
    private String unresolvedVariant;

    public FrogTrait() {
        super("frogtrait");
    }

    public Holder<FrogVariant> getVariant() {
        return variant == null ? defaultVariant() : variant;
    }

    @Override
    public void load(DataKey key) throws NPCLoadException {
        String raw = key.getString("variant");
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        unresolvedVariant = !raw.isEmpty() && (id == null || !BuiltInRegistries.FROG_VARIANT.containsKey(id)) ? raw : null;
        variant = parse(raw);
    }

    @Override
    public void save(DataKey key) {
        key.setString("variant", unresolvedVariant != null ? unresolvedVariant
                : getVariant().unwrapKey().map(k -> k.location().toString()).orElse(""));
    }

    @SuppressWarnings("unchecked")
    public static Holder<FrogVariant> parse(String raw) {
        if (raw == null || raw.isEmpty())
            return defaultVariant();
        ResourceLocation id = ResourceLocation.tryParse(raw.toLowerCase(Locale.ROOT));
        if (id == null || !BuiltInRegistries.FROG_VARIANT.containsKey(id))
            return defaultVariant();
        return BuiltInRegistries.FROG_VARIANT.getHolder(id).map(h -> (Holder<FrogVariant>) h)
                .orElseGet(FrogTrait::defaultVariant);
    }

    private static Holder<FrogVariant> defaultVariant() {
        return BuiltInRegistries.FROG_VARIANT.getHolderOrThrow(FrogVariant.TEMPERATE);
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Frog frog) {
            frog.setVariant(getVariant());
        }
    }

    public void setVariant(Holder<FrogVariant> variant) {
        unresolvedVariant = null;
        this.variant = variant == null ? defaultVariant() : variant;
    }
}
