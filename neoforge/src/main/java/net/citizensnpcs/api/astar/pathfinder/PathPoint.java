package net.citizensnpcs.api.astar.pathfinder;

import java.util.List;

import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * A single node in a calculated path.
 * <p>
 * Bukkit's {@code Vector} becomes {@link Vec3} and its {@code Block} becomes {@link BlockPos} — the callbacks always
 * concern the NPC's own level, which is reachable through {@link NPC#getEntity()}, so no level reference has to travel
 * with the position.
 */
public interface PathPoint {
    /**
     * Adds a path callback that will be executed if this path point is executed.
     */
    void addCallback(PathCallback callback);

    /**
     * Returns a new PathPoint at a given offset.
     */
    default PathPoint createAtOffset(Vec3 vector) {
        return createChild(floor(vector.x), floor(vector.y), floor(vector.z));
    }

    /**
     * Returns a new PathPoint at a given offset.
     */
    default PathPoint createAtOffset(Vec3 vector, float fixedCost) {
        return createChild(floor(vector.x), floor(vector.y), floor(vector.z), fixedCost);
    }

    /**
     * Returns a new PathPoint at a given point.
     */
    PathPoint createChild(int x, int y, int z);

    /**
     * Returns a new PathPoint at a given point.
     */
    PathPoint createChild(int x, int y, int z, float fixedCost);

    /**
     * Gets the destination vector
     */
    Vec3 getGoal();

    /**
     * Gets the parent PathPoint
     */
    PathPoint getParentPoint();

    /**
     * Gets the list of manual path vectors
     *
     * @see #setPathVectors(List)
     */
    List<Vec3> getPathVectors();

    /**
     * Gets the vector represented by this point
     */
    Vec3 getVector();

    /**
     * Sets the path vectors that will be used at pathfinding time. For example, setting a list of vectors to path
     * through in order to reach this pathpoint.
     */
    void setPathVectors(List<Vec3> vectors);

    /**
     * Sets the vector location of this point
     */
    void setVector(Vec3 vector);

    private static int floor(double value) {
        int i = (int) value;
        return value < i ? i - 1 : i;
    }

    public static interface PathCallback {
        /**
         * Run once the specified point is reached.
         *
         * @param npc
         *            The NPC
         * @param point
         *            The point that was reached
         */
        default void onReached(NPC npc, BlockPos point) {
        }

        /**
         * Run every tick when moving towards a specific block.
         *
         * @param npc
         *            The NPC
         * @param point
         *            The point
         * @param path
         *            The future path destinations
         * @param index
         *            The current path index
         */
        void run(NPC npc, BlockPos point, List<BlockPos> path, int index);
    }
}
