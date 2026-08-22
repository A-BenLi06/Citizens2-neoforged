package net.citizensnpcs.api.astar.pathfinder;

import net.citizensnpcs.api.astar.AStarGoal;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Reaching a block position, within a leeway in blocks.
 * <p>
 * The goal is snapped to block coordinates, as upstream does — the pathfinder works on whole blocks, and comparing
 * against a fractional destination would mean it could never report itself finished.
 */
public class VectorGoal implements AStarGoal<VectorNode> {
    private final Vec3 goal;
    private final float leeway;

    public VectorGoal(Location dest, float range) {
        this.leeway = range;
        this.goal = new Vec3(dest.getBlockX(), dest.getBlockY(), dest.getBlockZ());
    }

    public VectorGoal(BlockPos dest, float range) {
        this.leeway = range;
        this.goal = new Vec3(dest.getX(), dest.getY(), dest.getZ());
    }

    @Override
    public float g(VectorNode from, VectorNode to) {
        return from.distance(to);
    }

    public Vec3 getGoalVector() {
        return goal;
    }

    @Override
    public float getInitialCost(VectorNode node) {
        return node.distance(goal);
    }

    @Override
    public float h(VectorNode from) {
        return from.heuristicDistance(goal);
    }

    @Override
    public boolean isFinished(VectorNode node) {
        return goal.equals(node.location) || node.distance(goal) <= leeway;
    }
}
