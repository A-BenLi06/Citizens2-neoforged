package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.allay.Allay;

/** Whether an allay NPC is dancing, as it does near a jukebox. */
@TraitName("allaytrait")
public class AllayTrait extends Trait {
    @Persist
    private boolean dancing = false;

    public AllayTrait() {
        super("allaytrait");
    }

    public boolean isDancing() {
        return dancing;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Allay allay) {
            allay.setDancing(dancing);
        }
    }

    public void setDancing(boolean dance) {
        dancing = dance;
    }
}
