package net.citizensnpcs.trait;

import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.trait.Trait;
import net.citizensnpcs.api.trait.TraitName;
import net.minecraft.world.entity.Pose;

/**
 * Forces the NPC into a specific entity pose — sleeping, swimming, croaking and so on.
 * <p>
 * Upstream declares its own {@code EntityPose} enum, because Bukkit's {@code Pose} did not exist on every version it
 * supports and the NMS ids had to be written out by hand. Vanilla's {@link Pose} has exactly the same constants with
 * exactly the same ids, so the port persists that directly and the parallel enum is gone.
 */
@TraitName("entitypose")
public class EntityPoseTrait extends Trait {
    @Persist
    private Pose pose;

    public EntityPoseTrait() {
        super("entitypose");
    }

    public Pose getPose() {
        return pose;
    }

    @Override
    public void run() {
        if (pose == null || !npc.isSpawned())
            return;
        npc.getEntity().setPose(pose);
    }

    public void setPose(Pose pose) {
        this.pose = pose;
    }
}
