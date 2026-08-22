package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.MushroomCow;

/** A mooshroom NPC mushroom type. Vanilla names (RED / BROWN) match Bukkit. */
@TraitName("mushroomcowtrait")
public class MushroomCowTrait extends Trait {
    @Persist("variant")
    private MushroomCow.MushroomType variant;

    public MushroomCowTrait() {
        super("mushroomcowtrait");
    }

    public MushroomCow.MushroomType getVariant() {
        return variant;
    }

    @Override
    public void run() {
        if (variant != null && npc.getCosmeticEntity() instanceof MushroomCow cow) {
            cow.setVariant(variant);
        }
    }

    public void setVariant(MushroomCow.MushroomType variant) {
        this.variant = variant;
    }
}
