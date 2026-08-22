package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.PolarBear;

/** Whether a polar bear NPC stands on its hind legs. */
@TraitName("polarbeartrait")
public class PolarBearTrait extends Trait {
    @Persist
    private boolean rearing;

    public PolarBearTrait() {
        super("polarbeartrait");
    }

    public boolean isRearing() {
        return rearing;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof PolarBear bear) {
            bear.setStanding(rearing);
        }
    }

    public void setRearing(boolean rearing) {
        this.rearing = rearing;
    }
}
