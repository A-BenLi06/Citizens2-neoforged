package net.citizensnpcs.api.ai.flocking;

import java.util.Collection;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.phys.Vec3;

/**
 * Steers a flock of NPCs into line with each other.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Flocking_(behavior)">Flocking</a>
 */
public class AlignmentBehavior implements FlockBehavior {
    private final double weight;

    public AlignmentBehavior(double weight) {
        this.weight = weight;
    }

    @Override
    public Vec3 getVector(NPC npc, Collection<NPC> nearby) {
        Vec3 velocities = Vec3.ZERO;
        for (NPC neighbor : nearby) {
            velocities = velocities.add(neighbor.getEntity().getDeltaMovement());
        }
        Vec3 desired = velocities.scale(1.0 / nearby.size());
        return desired.subtract(npc.getEntity().getDeltaMovement()).scale(weight);
    }
}
