package net.citizensnpcs.api.astar.pathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import net.citizensnpcs.api.ai.NavigatorParameters;
import net.citizensnpcs.api.astar.AStarNode;
import net.citizensnpcs.api.astar.Plan;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.AdditionalNeighbourGenerator;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.PassableState;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.ReplacementNeighbourGenerator;
import net.citizensnpcs.api.astar.pathfinder.BlockExaminer.StandableState;
import net.citizensnpcs.api.util.Location;
import net.minecraft.world.phys.Vec3;

/**
 * One block position in a path being searched, and the search's view of its neighbours.
 * <p>
 * Whether a neighbour is usable is decided entirely by the configured {@link BlockExaminer}s: one of them has to say the
 * NPC can stand there, and one has to say it can pass through. That indirection is the whole point of Citizens having its
 * own pathfinder rather than using vanilla's — it is what lets doors, water, flight and custom rules be swapped per NPC.
 * <p>
 * Positions are {@link Vec3} rather than Bukkit's mutable {@code Vector}, so nodes cannot be corrupted by a caller that
 * holds on to one and edits it; upstream returns a defensive copy from {@code getVector} for exactly that reason.
 */
public class VectorNode extends AStarNode implements PathPoint {
    private float blockCost = -1;
    List<PathCallback> callbacks;
    private final PathInfo info;
    Vec3 location;
    List<Vec3> pathVectors;

    public VectorNode(VectorGoal goal, Location location, BlockSource source, NavigatorParameters params) {
        this(null, new Vec3(location.getBlockX(), location.getBlockY(), location.getBlockZ()),
                new PathInfo(source, params, goal));
    }

    public VectorNode(VectorNode parent, Vec3 location, PathInfo info) {
        super(parent);
        this.location = location;
        this.info = info;
    }

    @Override
    public void addCallback(PathCallback callback) {
        if (callbacks == null) {
            callbacks = new ArrayList<>();
        }
        callbacks.add(callback);
    }

    @Override
    public Plan buildPlan() {
        return new Path(orderedPath(), info.goal.getGoalVector());
    }

    @Override
    public VectorNode createChild(int x, int y, int z) {
        return new VectorNode(this, new Vec3(x, y, z), info);
    }

    @Override
    public PathPoint createChild(int x, int y, int z, float fixedCost) {
        VectorNode node = createChild(x, y, z);
        node.blockCost = node.getBlockCost() + fixedCost;
        return node;
    }

    /** Chebyshev distance, which is the right metric when diagonal steps cost the same as straight ones. */
    public float distance(Vec3 goal) {
        int dx = Math.abs(blockX() - (int) goal.x);
        int dy = Math.abs(blockY() - (int) goal.y);
        int dz = Math.abs(blockZ() - (int) goal.z);
        return Math.max(dx, Math.max(dy, dz));
    }

    public float distance(VectorNode to) {
        return distance(to.location);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null || getClass() != obj.getClass())
            return false;
        return Objects.equals(location, ((VectorNode) obj).location);
    }

    private float getBlockCost() {
        if (blockCost == -1) {
            blockCost = 0;
            for (BlockExaminer examiner : info.params.examiners()) {
                blockCost += examiner.getCost(info.blockSource, this);
            }
        }
        return blockCost;
    }

    @Override
    public Vec3 getGoal() {
        return info.goal.getGoalVector();
    }

    @Override
    public Iterable<AStarNode> getNeighbours() {
        List<PathPoint> neighbours = null;
        for (BlockExaminer examiner : info.params.examiners()) {
            if (examiner instanceof ReplacementNeighbourGenerator generator) {
                neighbours = generator.getNeighbours(info.blockSource, this);
                break;
            }
        }
        if (neighbours == null) {
            neighbours = getNeighbours(info.blockSource, this);
        }
        for (BlockExaminer examiner : info.params.examiners()) {
            if (examiner instanceof AdditionalNeighbourGenerator generator) {
                generator.addNeighbours(info.blockSource, this, neighbours);
            }
        }
        List<AStarNode> nodes = new ArrayList<>(neighbours.size());
        for (PathPoint sub : neighbours) {
            if (isPassable(sub)) {
                nodes.add((AStarNode) sub);
            }
        }
        return nodes;
    }

    public List<PathPoint> getNeighbours(BlockSource source, PathPoint point) {
        return getNeighbours(source, point, true);
    }

    /**
     * The twenty-six blocks around this one.
     *
     * @param checkPassable
     *            when set, a diagonal step is only offered if both of the straight steps beside it are usable — without
     *            it an NPC would try to cut corners through a wall
     */
    public List<PathPoint> getNeighbours(BlockSource source, PathPoint point, boolean checkPassable) {
        List<PathPoint> neighbours = new ArrayList<>(26);
        int baseX = blockX();
        int baseY = blockY();
        int baseZ = blockZ();
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                int modY = baseY + y;
                if (!source.isYWithinBounds(modY)) {
                    continue;
                }
                for (int z = -1; z <= 1; z++) {
                    if (x == 0 && y == 0 && z == 0) {
                        continue;
                    }
                    if (checkPassable && x != 0 && z != 0
                            && (!isPassable(point.createChild(baseX + x, modY, baseZ))
                                    || !isPassable(point.createChild(baseX, modY, baseZ + z)))) {
                        continue;
                    }
                    neighbours.add(point.createChild(baseX + x, modY, baseZ + z));
                }
            }
        }
        return neighbours;
    }

    @Override
    public PathPoint getParentPoint() {
        return (PathPoint) getParent();
    }

    @Override
    public List<Vec3> getPathVectors() {
        return pathVectors;
    }

    @Override
    public Vec3 getVector() {
        return location;
    }

    @Override
    public int hashCode() {
        return 31 + (location == null ? 0 : location.hashCode());
    }

    /**
     * The heuristic is scaled slightly above the true distance, which breaks ties between equally good paths and stops
     * the search fanning out over a plateau of identical scores.
     */
    public float heuristicDistance(Vec3 goal) {
        return (distance(goal) + getBlockCost()) * TIEBREAKER;
    }

    /**
     * A point is usable when some examiner will let the NPC stand there and some examiner will let it pass through. The
     * first examiner to claim it is standable gets asked about passability first, since it is the one that understands
     * the block.
     */
    private boolean isPassable(PathPoint mod) {
        boolean canStand = false;
        BlockExaminer found = null;
        for (BlockExaminer examiner : info.params.examiners()) {
            if (examiner.canStandAt(info.blockSource, mod) == StandableState.STANDABLE) {
                canStand = true;
                if (examiner.isPassable(info.blockSource, mod) == PassableState.PASSABLE)
                    return true;
                found = examiner;
                break;
            }
        }
        if (!canStand)
            return false;
        for (BlockExaminer examiner : info.params.examiners()) {
            if (examiner != found && examiner.isPassable(info.blockSource, mod) == PassableState.PASSABLE)
                return true;
        }
        return false;
    }

    @Override
    public void setPathVectors(List<Vec3> vectors) {
        pathVectors = vectors;
    }

    @Override
    public void setVector(Vec3 vector) {
        location = vector;
    }

    private int blockX() {
        return net.minecraft.util.Mth.floor(location.x);
    }

    private int blockY() {
        return net.minecraft.util.Mth.floor(location.y);
    }

    private int blockZ() {
        return net.minecraft.util.Mth.floor(location.z);
    }

    /** Shared by every node in one search: the blocks to read, the rules to apply and where it is heading. */
    private static class PathInfo {
        private final BlockSource blockSource;
        private final VectorGoal goal;
        private final NavigatorParameters params;

        private PathInfo(BlockSource source, NavigatorParameters params, VectorGoal goal) {
            this.blockSource = source;
            this.params = params;
            this.goal = goal;
        }
    }

    private static final float TIEBREAKER = 1.01f;
}
