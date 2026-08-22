package net.citizensnpcs.api.ai.flocking;

import java.util.Collection;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.world.phys.Vec3;

/**
 * One kind of flocking pull — cohesion, alignment or separation — combined with the others by a {@link Flocker}.
 * <p>
 * The displacement is a {@link Vec3} rather than Bukkit's mutable {@code Vector}, so implementations build a new vector
 * instead of accumulating into the one they were handed.
 */
public interface FlockBehavior {
    /**
     * @param npc
     *            the NPC being steered
     * @param nearby
     *            the NPCs to consider part of the flock
     * @return the displacement this behaviour wants to apply
     */
    Vec3 getVector(NPC npc, Collection<NPC> nearby);
}
