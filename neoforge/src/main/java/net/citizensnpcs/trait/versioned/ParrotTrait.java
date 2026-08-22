package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.Parrot;

/** A parrot NPC colour. Vanilla variant names match Bukkit, so saves read back directly. */
@TraitName("parrottrait")
public class ParrotTrait extends Trait {
    @Persist
    private Parrot.Variant variant = Parrot.Variant.BLUE;

    public ParrotTrait() {
        super("parrottrait");
    }

    public Parrot.Variant getVariant() {
        return variant;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Parrot parrot) {
            parrot.setVariant(variant);
        }
    }

    public void setVariant(Parrot.Variant variant) {
        this.variant = variant == null ? Parrot.Variant.BLUE : variant;
    }
}
