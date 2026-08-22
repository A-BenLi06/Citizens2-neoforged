package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.phys.Vec3;

/**
 * Enable/disable Minecraft's gravity.
 */
@TraitName("gravity")
public class Gravity extends Trait {
    @Persist("enabled")
    private boolean nogravity;

    public Gravity() {
        super("gravity");
    }

    private void applyImmediately() {
        if (!nogravity || npc.getEntity() == null)
            return;
        // zero the vertical component too, or the NPC keeps whatever fall speed it had when gravity was switched off
        Vec3 movement = npc.getEntity().getDeltaMovement();
        npc.getEntity().setDeltaMovement(movement.x, 0, movement.z);
        npc.getEntity().setNoGravity(true);
    }

    public boolean hasGravity() {
        return !nogravity;
    }

    @Override
    public void onSpawn() {
        applyImmediately();
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        npc.getEntity().setNoGravity(nogravity);
    }

    /**
     * Set whether to have gravity or not
     */
    public void setHasGravity(boolean hasGravity) {
        nogravity = !hasGravity;
    }

    public boolean toggle() {
        nogravity = !nogravity;
        applyImmediately();
        return nogravity;
    }
}
