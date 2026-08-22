package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.monster.Vex;

/** Whether a vex NPC shows its charging (attacking) pose. */
@TraitName("vextrait")
public class VexTrait extends Trait {
    @Persist("charging")
    private Boolean charging;

    public VexTrait() {
        super("vextrait");
    }

    public Boolean isCharging() {
        return charging;
    }

    @Override
    public void run() {
        if (charging != null && npc.getCosmeticEntity() instanceof Vex vex) {
            vex.setIsCharging(charging);
        }
    }

    public void setCharging(Boolean charging) {
        this.charging = charging;
    }
}
