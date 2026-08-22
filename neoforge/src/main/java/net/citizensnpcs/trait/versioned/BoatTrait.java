package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.vehicle.Boat;

/**
 * The wood a boat NPC is made of.
 * <p>
 * Upstream's version of this trait deliberately does nothing on 1.21 and above: 1.21.2 replaced the single boat entity
 * with one entity type per wood, and upstream no longer supports the releases in between. 1.21.1 is one of those
 * releases — here a boat still carries its wood as a variant, so the trait works and is implemented rather than left
 * inert. The variant is a NeoForge-extensible enum, so woods added by other mods work with no code change.
 */
@TraitName("boattrait")
public class BoatTrait extends Trait {
    @Persist
    private Boat.Type type;

    public BoatTrait() {
        super("boattrait");
    }

    public Boat.Type getType() {
        return type;
    }

    @Override
    public void onSpawn() {
        apply();
    }

    private void apply() {
        if (type != null && npc.getCosmeticEntity() instanceof Boat boat) {
            boat.setVariant(type);
        }
    }

    public void setType(Boat.Type type) {
        this.type = type;
        apply();
    }
}
