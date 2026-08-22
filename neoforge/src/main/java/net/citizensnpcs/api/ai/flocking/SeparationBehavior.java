package net.citizensnpcs.api.ai.flocking;

import java.util.Collection;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.phys.Vec3;

/**
 * Steers a flock of NPCs away from each other.
 *
 * @see <a href="https://en.wikipedia.org/wiki/Flocking_(behavior)">Flocking</a>
 */
public class SeparationBehavior implements FlockBehavior {
    private double separation = 0.5;
    private final double weight;

    public SeparationBehavior(double weight) {
        this.weight = weight;
    }

    public SeparationBehavior(double weight, double separation) {
        this.separation = separation;
        this.weight = weight;
    }

    @Override
    public Vec3 getVector(NPC npc, Collection<NPC> nearby) {
        Vec3 pos = npc.getEntity().position();
        Vec3 steering = Vec3.ZERO;
        int count = 0;
        for (NPC neighbor : nearby) {
            // horizontal only: pushing flockmates apart vertically would fight gravity
            Vec3 diff = pos.subtract(neighbor.getEntity().position()).multiply(1, 0, 1);
            double dist = diff.length();
            if (dist > separation || dist == 0) {
                continue;
            }
            steering = steering.add(diff.normalize().scale(1 / (dist * 50)));
            count++;
        }
        return steering.scale(1.0 / Math.max(1, count)).multiply(1, 0, 1).scale(weight);
    }
}
