package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.TropicalFish;
import net.minecraft.world.item.DyeColor;

/**
 * A tropical fish NPC's pattern and its two colours. All three sets of names match Bukkit.
 * <p>
 * Vanilla packs the pattern and both colours into a single synced int. Nothing public writes it as a whole — the public
 * setVariant replaces only the pattern, keeping the colours — so the packer and the raw setter are both widened. Writing
 * all three together also avoids the two intermediate frames a per-field setter would show.
 */
@TraitName("tropicalfishtrait")
public class TropicalFishTrait extends Trait {
    @Persist
    private DyeColor bodyColor = DyeColor.BLUE;
    @Persist
    private TropicalFish.Pattern pattern = TropicalFish.Pattern.BRINELY;
    @Persist
    private DyeColor patternColor = DyeColor.BLUE;

    public TropicalFishTrait() {
        super("tropicalfishtrait");
    }

    public DyeColor getBodyColor() {
        return bodyColor;
    }

    public TropicalFish.Pattern getPattern() {
        return pattern;
    }

    public DyeColor getPatternColor() {
        return patternColor;
    }

    @Override
    public void run() {
        if (!(npc.getCosmeticEntity() instanceof TropicalFish fish))
            return;
        fish.setPackedVariant(TropicalFish.packVariant(pattern == null ? TropicalFish.Pattern.BRINELY : pattern,
                bodyColor == null ? DyeColor.BLUE : bodyColor, patternColor == null ? DyeColor.BLUE : patternColor));
    }

    public void setBodyColor(DyeColor color) {
        bodyColor = color;
    }

    public void setPattern(TropicalFish.Pattern pattern) {
        this.pattern = pattern;
    }

    public void setPatternColor(DyeColor color) {
        patternColor = color;
    }
}
