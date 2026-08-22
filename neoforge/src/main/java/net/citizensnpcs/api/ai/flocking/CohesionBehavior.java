package net.citizensnpcs.api.ai.flocking;

import java.util.Collection;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.phys.Vec3;

/**
 * Steers a flock of NPCs towards each other.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Flocking_(behavior)">Flocking</a>
 */
public class CohesionBehavior implements FlockBehavior {
    private final double weight;

    public CohesionBehavior(double weight) {
        this.weight = weight;
    }

    @Override
    public Vec3 getVector(NPC npc, Collection<NPC> nearby) {
        Vec3 positions = Vec3.ZERO;
        for (NPC neighbor : nearby) {
            positions = positions.add(neighbor.getEntity().position());
        }
        Vec3 center = positions.scale(1.0 / nearby.size());
        Vec3 away = npc.getEntity().position().subtract(center);
        return away.length() == 0 ? Vec3.ZERO : away.normalize().scale(weight);
    }
}
