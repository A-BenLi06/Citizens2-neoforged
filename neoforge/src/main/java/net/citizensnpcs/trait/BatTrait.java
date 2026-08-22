package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.ambient.Bat;

/**
 * Persists whether a bat NPC is awake (flying) or hanging.
 */
@TraitName("battrait")
public class BatTrait extends Trait {
    @Persist
    private boolean awake;

    public BatTrait() {
        super("battrait");
    }

    public boolean isAwake() {
        return awake;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Bat bat) {
            // vanilla models the opposite state: resting is hanging from a ceiling
            bat.setResting(!awake);
        }
    }

    public void setAwake(boolean awake) {
        this.awake = awake;
    }
}
