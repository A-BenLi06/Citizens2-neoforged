package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;

/**
 * Makes the NPC appear to be sneaking.
 */
@TraitName("sneak")
public class SneakTrait extends Trait {
    @Persist
    private boolean sneaking;

    public SneakTrait() {
        super("sneak");
    }

    private void apply() {
        if (!npc.isSpawned())
            return;
        npc.getEntity().setShiftKeyDown(sneaking);
        npc.getEntity().setPose(sneaking ? net.minecraft.world.entity.Pose.CROUCHING
                : net.minecraft.world.entity.Pose.STANDING);
    }

    public boolean isSneaking() {
        return sneaking;
    }

    @Override
    public void onAttach() {
        apply();
    }

    @Override
    public void onSpawn() {
        apply();
    }

    public void setSneaking(boolean sneak) {
        sneaking = sneak;
        apply();
    }
}
