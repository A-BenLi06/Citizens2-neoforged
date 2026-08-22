package net.citizensnpcs.api.astar.pathfinder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.stream.Collectors;

import net.citizensnpcs.api.astar.Agent;
import net.citizensnpcs.api.astar.Plan;
import net.citizensnpcs.api.astar.pathfinder.PathPoint.PathCallback;
import net.citizensnpcs.api.npc.NPC;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * A finished path: the block centres to walk through, in order, with any callbacks attached to them.
 * <p>
 * The raw A* result is one node per block, which makes for a jagged walk and a lot of waypoints. It is thinned with
 * Ramer-Douglas-Peucker before use, splitting the run at any point that carries a callback so that a simplification can
 * never drop a point something is waiting on.
 * <p>
 * Callbacks receive a {@link BlockPos} rather than Bukkit's {@code Block}, which carries its world with it; the NPC is
 * passed alongside, so the level is always available where one is needed.
 */
public class Path implements Plan {
    private List<BlockPos> blockList;
    private int index = 0;
    private final PathEntry[] path;

    public Path(Collection<Vec3> vectors) {
        this.path = vectors.stream().map(input -> new PathEntry(input, Collections.emptyList()))
                .toArray(PathEntry[]::new);
    }

    Path(Iterable<VectorNode> unfiltered, Vec3 goal) {
        List<PathEntry> entries = new ArrayList<>();
        for (VectorNode node : unfiltered) {
            if (node.getPathVectors() != null) {
                for (Vec3 vector : node.getPathVectors()) {
                    entries.add(new PathEntry(vector, node.callbacks));
                }
            } else {
                // the walk targets block centres, not corners, or the NPC clips the edge of every block it crosses
                entries.add(new PathEntry(node.getVector().add(0.5, 0, 0.5), node.callbacks));
            }
        }
        PathEntry goalEntry = new PathEntry(goal, entries.get(entries.size() - 1).callbacks);
        Vec3 last = entries.get(entries.size() - 1).vector;
        if (Mth.floor(last.x) == Mth.floor(goal.x) && Mth.floor(last.y) == Mth.floor(goal.y)
                && Mth.floor(last.z) == Mth.floor(goal.z)) {
            entries.set(entries.size() - 1, goalEntry);
        } else {
            entries.add(goalEntry);
        }
        this.path = ramerDouglasPeucker(entries, 0.75).toArray(new PathEntry[0]);
    }

    public List<BlockPos> getBlocks() {
        return Arrays.stream(path).map(p -> BlockPos.containing(p.vector)).collect(Collectors.toList());
    }

    public Vec3 getCurrentVector() {
        return path[index].vector;
    }

    public Iterable<Vec3> getPath() {
        return Arrays.stream(path).map(entry -> entry.vector).collect(Collectors.toList());
    }

    @Override
    public boolean isComplete() {
        return index >= path.length;
    }

    public void run(NPC npc) {
        if (!isComplete()) {
            path[index].run(npc);
        }
    }

    @Override
    public String toString() {
        return Arrays.toString(path);
    }

    @Override
    public void update(Agent agent) {
        if (isComplete())
            return;
        path[index++].onComplete((NPC) agent);
    }

    private class PathEntry {
        BlockPos cache;
        final List<PathCallback> callbacks;
        final Vec3 vector;

        private PathEntry(Vec3 vector, List<PathCallback> callbacks) {
            this.vector = vector;
            this.callbacks = callbacks;
        }

        void onComplete(NPC npc) {
            if (callbacks == null)
                return;
            if (cache == null) {
                cache = BlockPos.containing(vector);
            }
            for (PathCallback callback : callbacks) {
                callback.onReached(npc, cache);
            }
        }

        void run(NPC npc) {
            if (callbacks == null)
                return;
            if (blockList == null) {
                blockList = getBlocks();
                cache = BlockPos.containing(vector);
            }
            for (PathCallback callback : callbacks) {
                callback.run(npc, cache, blockList, index);
            }
        }

        @Override
        public String toString() {
            return vector.toString();
        }
    }

    /**
     * Thins the path, keeping every point that carries a callback as a fixed split so simplification cannot remove one.
     */
    private static List<PathEntry> ramerDouglasPeucker(List<PathEntry> points, double epsilon) {
        if (points.size() < 3)
            return points;
        List<Integer> splitIndices = new ArrayList<>();
        splitIndices.add(0);
        for (int i = 1; i < points.size() - 1; i++) {
            if (points.get(i).callbacks != null && !points.get(i).callbacks.isEmpty()) {
                splitIndices.add(i);
            }
        }
        splitIndices.add(points.size() - 1);

        List<PathEntry> result = new ArrayList<>();
        for (int s = 0; s < splitIndices.size() - 1; s++) {
            List<PathEntry> segment = points.subList(splitIndices.get(s), splitIndices.get(s + 1) + 1);
            List<PathEntry> simplified = ramerDouglasPeuckerSegment(segment, epsilon);
            // the boundary point belongs to the next segment too, so it is only emitted once
            if (s == splitIndices.size() - 2) {
                result.addAll(simplified);
            } else {
                result.addAll(simplified.subList(0, simplified.size() - 1));
            }
        }
        return result;
    }

    private static List<PathEntry> ramerDouglasPeuckerSegment(List<PathEntry> points, double epsilon) {
        if (points.size() < 3)
            return new ArrayList<>(points);
        int n = points.size();
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;

        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[] { 0, n - 1 });
        while (!stack.isEmpty()) {
            int[] range = stack.pop();
            int start = range[0];
            int end = range[1];
            double dmax = 0;
            int found = -1;

            Vec3 a = points.get(start).vector;
            Vec3 b = points.get(end).vector;
            double abx = b.x - a.x;
            double aby = b.y - a.y;
            double abz = b.z - a.z;
            double length = abx * abx + aby * aby + abz * abz;

            for (int i = start + 1; i < end; i++) {
                Vec3 p = points.get(i).vector;
                double d;
                if (length < 1e-9) {
                    // the segment collapsed to a point, so the distance is just to that point
                    d = p.distanceTo(a);
                } else {
                    double apx = p.x - a.x;
                    double apy = p.y - a.y;
                    double apz = p.z - a.z;
                    double cx = apy * abz - apz * aby;
                    double cy = apz * abx - apx * abz;
                    double cz = apx * aby - apy * abx;
                    double crossLength = cx * cx + cy * cy + cz * cz;
                    if (crossLength <= epsilon * epsilon * length) {
                        continue;
                    }
                    d = Math.sqrt(crossLength / length);
                }
                if (d > dmax) {
                    dmax = d;
                    found = i;
                }
            }
            if (dmax > epsilon && found != -1) {
                keep[found] = true;
                stack.push(new int[] { start, found });
                stack.push(new int[] { found, end });
            }
        }
        List<PathEntry> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                result.add(points.get(i));
            }
        }
        return result;
    }
}
