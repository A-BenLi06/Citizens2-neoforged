package net.citizensnpcs.api.ai.flocking;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.trait.Trait;
import net.minecraft.world.phys.Vec3;

/**
 * A simple flocking controller, meant to be run every tick — usually from a {@link Trait#run()}.
 *
 * @see NPCFlock
 * @see FlockBehavior
 * @see <a href="https://en.wikipedia.org/wiki/Flocking_(behavior)">Flocking</a>
 */
public class Flocker implements Runnable {
    private final List<FlockBehavior> behaviors;
    private final NPCFlock flock;
    private double maxForce = 1.5;
    private final NPC npc;

    public Flocker(NPC npc, NPCFlock flock, FlockBehavior... behaviors) {
        this.npc = npc;
        this.flock = flock;
        this.behaviors = Arrays.asList(behaviors);
    }

    @Override
    public void run() {
        if (!npc.isSpawned())
            return;
        Collection<NPC> nearby = flock.getNearby(npc).stream().filter(NPC::isSpawned).toList();
        if (nearby.isEmpty())
            return;
        Vec3 base = Vec3.ZERO;
        for (FlockBehavior behavior : behaviors) {
            base = base.add(behavior.getVector(npc, nearby));
        }
        base = clip(maxForce, base);
        npc.getEntity().setDeltaMovement(npc.getEntity().getDeltaMovement().add(base));
        npc.getEntity().hasImpulse = true;
    }

    /**
     * @param maxForce
     *            the longest the combined flocking vector may be
     */
    public void setMaxForce(double maxForce) {
        if (maxForce == 0)
            throw new IllegalArgumentException();
        this.maxForce = maxForce;
    }

    private static Vec3 clip(double max, Vec3 vector) {
        return vector.length() > max ? vector.normalize().scale(max) : vector;
    }

    /** Sample weight indicating a high amount of influence from flocking. */
    public static double HIGH_INFLUENCE = 1.0 / 20.0;
    /** Sample weight indicating a low amount of influence from flocking. */
    public static double LOW_INFLUENCE = 1.0 / 200.0;
}
