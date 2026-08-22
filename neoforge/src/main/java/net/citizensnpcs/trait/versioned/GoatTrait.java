package net.citizensnpcs.trait.versioned;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.animal.goat.Goat;

/**
 * Which horns a goat NPC has.
 * <p>
 * Vanilla only ever knocks a horn off and never puts one back, so neither flag has a setter and the synced keys are
 * written directly.
 */
@TraitName("goattrait")
public class GoatTrait extends Trait {
    @Persist
    private boolean leftHorn = true;
    @Persist
    private boolean rightHorn = true;

    public GoatTrait() {
        super("goattrait");
    }

    public boolean isLeftHorn() {
        return leftHorn;
    }

    public boolean isRightHorn() {
        return rightHorn;
    }

    @Override
    public void run() {
        if (npc.getCosmeticEntity() instanceof Goat goat) {
            goat.getEntityData().set(Goat.DATA_HAS_LEFT_HORN, leftHorn);
            goat.getEntityData().set(Goat.DATA_HAS_RIGHT_HORN, rightHorn);
        }
    }

    public void setLeftHorn(boolean horn) {
        leftHorn = horn;
    }

    public void setRightHorn(boolean horn) {
        rightHorn = horn;
    }
}
