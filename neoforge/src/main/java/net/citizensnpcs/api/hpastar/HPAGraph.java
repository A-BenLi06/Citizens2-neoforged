package net.citizensnpcs.api.hpastar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.CancellationException;

import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.MinecraftBlockExaminer;
import net.citizensnpcs.api.astar.pathfinder.Path;
import net.citizensnpcs.api.util.Location;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.phys.Vec3;

/**
 * Hierarchical pathfinding: block searches within 16x8x16 clusters, portal searches within successively larger
 * clusters, and recursive refinement back to individual blocks. Coordinates use the source's actual height bounds.
 * This is a two-block-tall land-walking diagnostic, independent of an NPC's navigator and movement capabilities.
 *
 * <p>Unlike the upstream entrance sampling heuristic, every valid boundary crossing is retained. This preserves
 * narrow/stepped connections and avoids losing paths at vertical cluster boundaries, at the cost of more portals.
 * Higher clusters are aligned globally, including the 128-block level that spans multiple 64-block loading regions.
 *
 * <p>Use an immutable source for background work. Mutations to a live source require marking affected regions dirty.
 * All expensive loops observe interruption; an interrupted build is retried on the next operation. Query nodes are
 * always removed, and refinement failure returns an empty path rather than an unchecked straight segment.
 */
public class HPAGraph {
    public static final int REGION_SIZE = 64;
    public static final int BASE_CLUSTER_SIZE = 16;
    public static final int BASE_CLUSTER_HEIGHT = 8;
    public static final int MAX_DEPTH = 3;
    static final int[][] NEIGHBOURS;
    static final float[] MOVE_COSTS;

    private final BlockSource source;
    private final int originX, originZ, minY, maxY;
    private final Set<Region> regions = new LinkedHashSet<>();
    private final Set<Region> dirty = new LinkedHashSet<>();
    private final List<Map<GridKey, HPACluster>> indexes = new ArrayList<>();
    private final List<List<HPACluster>> levels = new ArrayList<>();
    /** Read-only views, retained for graph diagnostics. */
    public final List<List<HPACluster>> clusters;
    private boolean rebuildRequired;

    /** The source must also expose native height access, or use the constructor with explicit bounds. */
    public HPAGraph(BlockSource source, int cx, int cy, int cz) {
        this(source, cx, cz, height(source).getMinBuildHeight(), height(source).getMaxBuildHeight());
    }

    public HPAGraph(BlockSource source, int originX, int originZ, int minY, int maxY) {
        this.source = Objects.requireNonNull(source);
        if (minY >= maxY) throw new IllegalArgumentException("Empty height range");
        Math.subtractExact(maxY, minY);
        this.originX = originX;
        this.originZ = originZ;
        this.minY = minY;
        this.maxY = maxY;
        List<List<HPACluster>> views = new ArrayList<>();
        for (int depth = 0; depth <= MAX_DEPTH; depth++) {
            levels.add(new ArrayList<>());
            indexes.add(new LinkedHashMap<>());
            views.add(Collections.unmodifiableList(levels.get(depth)));
        }
        clusters = Collections.unmodifiableList(views);
    }

    private static LevelHeightAccessor height(BlockSource source) {
        if (source instanceof LevelHeightAccessor accessor) return accessor;
        throw new IllegalArgumentException("Supply explicit HPA height bounds for this BlockSource");
    }

    public synchronized void addClusters(int x, int z) {
        if (regions.add(regionAt(x, z))) rebuildRequired = true;
        ensureBuilt();
    }

    /** Adds all requested loading regions before building the hierarchy once. Y is ignored. */
    public synchronized void addClusters(Iterable<BlockPos> positions) {
        for (BlockPos position : positions) {
            checkInterrupted();
            if (regions.add(regionAt(position.getX(), position.getZ()))) rebuildRequired = true;
        }
        ensureBuilt();
    }

    public synchronized void applyPendingPatches() {
        if (!dirty.isEmpty()) {
            regions.addAll(dirty);
            dirty.clear();
            rebuildRequired = true;
        }
        ensureBuilt();
    }

    private void clearStorage() {
        levels.forEach(List::clear);
        indexes.forEach(Map::clear);
    }

    private void ensureBuilt() {
        checkInterrupted();
        if (!rebuildRequired) return;
        clearStorage();
        try {
            for (Region region : regions) {
                int baseX = Math.toIntExact((long) region.x * REGION_SIZE + originX);
                int baseZ = Math.toIntExact((long) region.z * REGION_SIZE + originZ);
                for (long y = minY; y < maxY; y += BASE_CLUSTER_HEIGHT) {
                    checkInterrupted();
                    for (int dx = 0; dx < REGION_SIZE; dx += BASE_CLUSTER_SIZE) {
                        for (int dz = 0; dz < REGION_SIZE; dz += BASE_CLUSTER_SIZE) {
                            HPACluster cluster = new HPACluster(this, 0, BASE_CLUSTER_SIZE, BASE_CLUSTER_HEIGHT,
                                    Math.addExact(baseX, dx), (int) y, Math.addExact(baseZ, dz));
                            if (cluster.hasWalkableNodes()) addCluster(0, cluster);
                        }
                    }
                }
            }
            for (HPACluster cluster : levels.get(0)) {
                checkInterrupted();
                GridKey key = gridAt(0, cluster.clusterX, cluster.clusterY, cluster.clusterZ);
                for (int[] offset : NEIGHBOURS) {
                    if (offset[0] < 0 || offset[0] == 0 && offset[1] < 0
                            || offset[0] == 0 && offset[1] == 0 && offset[2] < 0) continue;
                    HPACluster other = indexes.get(0).get(new GridKey(key.x + offset[0], key.y + offset[1], key.z + offset[2]));
                    if (other != null) cluster.connectBoundary(other);
                }
            }
            for (HPACluster cluster : levels.get(0)) cluster.connectIntra();
            for (int depth = 1; depth <= MAX_DEPTH; depth++) {
                Map<GridKey, List<HPACluster>> children = new LinkedHashMap<>();
                for (HPACluster child : levels.get(depth - 1)) {
                    checkInterrupted();
                    children.computeIfAbsent(gridAt(depth, child.clusterX, child.clusterY, child.clusterZ),
                            unused -> new ArrayList<>()).add(child);
                }
                int size = BASE_CLUSTER_SIZE << depth, height = BASE_CLUSTER_HEIGHT << depth;
                for (var entry : children.entrySet()) {
                    checkInterrupted();
                    GridKey key = entry.getKey();
                    HPACluster cluster = new HPACluster(this, depth, size, height,
                            Math.toIntExact((long) originX + (long) key.x * size),
                            Math.toIntExact((long) minY + (long) key.y * height),
                            Math.toIntExact((long) originZ + (long) key.z * size));
                    addCluster(depth, cluster);
                    cluster.buildFrom(entry.getValue());
                }
            }
            rebuildRequired = false;
        } catch (RuntimeException failure) {
            clearStorage();
            throw failure;
        }
    }

    private void addCluster(int depth, HPACluster cluster) {
        indexes.get(depth).put(gridAt(depth, cluster.clusterX, cluster.clusterY, cluster.clusterZ), cluster);
        levels.get(depth).add(cluster);
    }

    public synchronized Path findPath(Location start, Location goal) {
        if (start == null || goal == null || start.getWorld() != goal.getWorld()
                || !finite(start) || !finite(goal)) return new Path(List.of());
        return findPath(new BlockPos(start.getBlockX(), start.getBlockY(), start.getBlockZ()),
                new BlockPos(goal.getBlockX(), goal.getBlockY(), goal.getBlockZ()));
    }

    private static boolean finite(Location location) {
        return Double.isFinite(location.getX()) && Double.isFinite(location.getY()) && Double.isFinite(location.getZ());
    }

    public synchronized Path findPath(BlockPos start, BlockPos goal) {
        return findPath(start, goal, MAX_DEPTH);
    }

    /** Chooses the highest abstraction used by the query; zero still searches the base portal graph. */
    public synchronized Path findPath(BlockPos start, BlockPos goal, int depth) {
        if (depth < 0 || depth > MAX_DEPTH) throw new IllegalArgumentException("Invalid HPA level");
        applyPendingPatches();
        if (!walkable(start.getX(), start.getY(), start.getZ()) || !walkable(goal.getX(), goal.getY(), goal.getZ())
                || getClusterAt(0, start) == null || getClusterAt(0, goal) == null) return new Path(List.of());
        List<Membership> inserted = new ArrayList<>();
        HPAGraphNode from = new HPAGraphNode(start.getX(), start.getY(), start.getZ());
        HPAGraphNode to = from.position.equals(goal) ? from : new HPAGraphNode(goal.getX(), goal.getY(), goal.getZ());
        try {
            for (int level = 0; level <= depth; level++) {
                from = insertQueryNode(getClusterAt(level, start), from, inserted);
                to = insertQueryNode(getClusterAt(level, goal), to, inserted);
            }
            AStarSolution solution = pathfind(from, to, depth);
            List<Vec3> concrete = new ArrayList<>();
            if (!refine(solution, depth, concrete)) return new Path(List.of());
            return new Path(concrete);
        } finally {
            for (int i = inserted.size() - 1; i >= 0; i--) {
                Membership membership = inserted.get(i);
                membership.cluster.remove(membership.node);
            }
        }
    }

    private HPAGraphNode insertQueryNode(HPACluster cluster, HPAGraphNode node, List<Membership> inserted) {
        HPAGraphNode existing = cluster.getNode(node.position);
        if (existing != null) return existing;
        // Record ownership before insertion, which may be interrupted while calculating its edges.
        inserted.add(new Membership(cluster, node));
        cluster.insert(node);
        return node;
    }

    private boolean refine(AStarSolution solution, int depth, List<Vec3> result) {
        if (solution.path.isEmpty()) return false;
        append(result, solution.path.getFirst().node.toVector());
        for (int i = 1; i < solution.path.size(); i++) {
            checkInterrupted();
            HPAGraphEdge edge = solution.path.get(i).getEdge();
            if (edge.type == HPAGraphEdge.EdgeType.INTER) {
                if (!canMove(edge.from.position, edge.to.position)) return false;
                append(result, edge.to.toVector());
            } else {
                HPACluster cluster = getClusterAt(depth, edge.from.position);
                if (cluster == null || !cluster.containsPoint(edge.to.x, edge.to.y, edge.to.z)) return false;
                if (depth == 0) {
                    List<Vec3> segment = cluster.refine(edge.from, edge.to);
                    if (segment.isEmpty()) return false;
                    for (Vec3 position : segment) append(result, position);
                } else if (!refine(pathfind(edge.from, edge.to, depth - 1, cluster), depth - 1, result)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void append(List<Vec3> result, Vec3 position) {
        if (result.isEmpty() || !result.getLast().equals(position)) result.add(position);
    }

    HPACluster getClusterAt(int depth, BlockPos position) {
        return indexes.get(depth).get(gridAt(depth, position.getX(), position.getY(), position.getZ()));
    }

    private GridKey gridAt(int depth, int x, int y, int z) {
        return new GridKey(Math.toIntExact(Math.floorDiv((long) x - originX, BASE_CLUSTER_SIZE << depth)),
                Math.toIntExact(Math.floorDiv((long) y - minY, BASE_CLUSTER_HEIGHT << depth)),
                Math.toIntExact(Math.floorDiv((long) z - originZ, BASE_CLUSTER_SIZE << depth)));
    }

    public synchronized void invalidateRegion(int x, int z) {
        markDirtyBlock(x, z);
        applyPendingPatches();
    }

    public synchronized void markDirtyBlock(int x, int z) {
        dirty.add(regionAt(x, z));
    }

    public synchronized void markDirtyChunk(int chunkX, int chunkZ) {
        int x = Math.multiplyExact(chunkX, 16), z = Math.multiplyExact(chunkZ, 16);
        // A chunk may straddle regions because the graph origin need not be chunk-aligned.
        for (int dx : new int[] { 0, 15 }) for (int dz : new int[] { 0, 15 }) markDirtyBlock(x + dx, z + dz);
    }

    AStarSolution pathfind(HPAGraphNode from, HPAGraphNode to, int depth) {
        return pathfind(from, to, depth, null);
    }

    private AStarSolution pathfind(HPAGraphNode from, HPAGraphNode to, int depth, HPACluster bounds) {
        Map<HPAGraphNode, Float> costs = new HashMap<>();
        PriorityQueue<HPAGraphAStarNode> frontier = new PriorityQueue<>();
        HPAGraphAStarNode start = new HPAGraphAStarNode(from, null);
        start.h = (float) from.distance(to);
        costs.put(from, 0F);
        frontier.add(start);
        while (!frontier.isEmpty()) {
            checkInterrupted();
            HPAGraphAStarNode current = frontier.remove();
            if (current.g > costs.getOrDefault(current.node, Float.POSITIVE_INFINITY)) continue;
            // A generated goal can still have a more expensive path than another open node.
            if (current.node.equals(to)) return new AStarSolution(current.reconstructSolution(), current.g);
            for (HPAGraphEdge edge : current.node.getEdges(depth)) {
                if (bounds != null && !bounds.containsPoint(edge.to.x, edge.to.y, edge.to.z)) continue;
                float cost = current.g + edge.weight;
                if (cost >= costs.getOrDefault(edge.to, Float.POSITIVE_INFINITY)) continue;
                costs.put(edge.to, cost);
                HPAGraphAStarNode next = new HPAGraphAStarNode(edge.to, edge);
                next.parent = current;
                next.g = cost;
                next.h = (float) edge.to.distance(to);
                frontier.add(next);
            }
        }
        return new AStarSolution(List.of(), Float.POSITIVE_INFINITY);
    }

    private Region regionAt(int x, int z) {
        return new Region(Math.toIntExact(Math.floorDiv((long) x - originX, REGION_SIZE)),
                Math.toIntExact(Math.floorDiv((long) z - originZ, REGION_SIZE)));
    }

    public boolean walkable(int x, int y, int z) {
        return y > minY && y < (long) maxY - 1 && clearColumn(x, y, z)
                && source.isBlockAvailable(x, y - 1, z) && MinecraftBlockExaminer.canStandOn(source.getBlockAt(x, y - 1, z));
    }

    boolean clearColumn(int x, int y, int z) {
        return y >= minY && y < (long) maxY - 1 && source.isBlockAvailable(x, y, z)
                && source.isBlockAvailable(x, y + 1, z) && MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, y, z))
                && MinecraftBlockExaminer.canStandIn(source.getBlockAt(x, y + 1, z));
    }

    public boolean canMove(BlockPos from, BlockPos to) {
        return canMove(from.getX(), from.getY(), from.getZ(), to.getX(), to.getY(), to.getZ(), null);
    }

    boolean canMove(int x, int y, int z, int nx, int ny, int nz, HPACluster cache) {
        long dx = (long) nx - x, dy = (long) ny - y, dz = (long) nz - z;
        if (Math.abs(dx) > 1 || Math.abs(dy) > 1 || Math.abs(dz) > 1 || dx == 0 && dy == 0 && dz == 0) return false;
        if (cache == null ? !walkable(x, y, z) || !walkable(nx, ny, nz)
                : !cache.walkable(x, y, z) || !cache.walkable(nx, ny, nz)) return false;
        int highY = Math.max(y, ny);
        if (!clear(x, highY, z, cache) || !clear(nx, highY, nz, cache)) return false;
        return dx == 0 || dz == 0 || clear(x, highY, nz, cache) && clear(nx, highY, z, cache);
    }

    private boolean clear(int x, int y, int z, HPACluster cache) {
        return cache == null ? clearColumn(x, y, z) : cache.clearColumn(x, y, z);
    }

    static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("HPA calculation interrupted");
    }

    private record Region(int x, int z) {}
    private record GridKey(int x, int y, int z) {}
    private record Membership(HPACluster cluster, HPAGraphNode node) {}

    static {
        List<int[]> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) for (int dz = -1; dz <= 1; dz++) {
            if (dx != 0 || dy != 0 || dz != 0) offsets.add(new int[] { dx, dy, dz });
        }
        NEIGHBOURS = offsets.toArray(int[][]::new);
        MOVE_COSTS = new float[NEIGHBOURS.length];
        for (int i = 0; i < NEIGHBOURS.length; i++) {
            int[] offset = NEIGHBOURS[i];
            MOVE_COSTS[i] = (float) Math.sqrt(offset[0] * offset[0] + offset[1] * offset[1] + offset[2] * offset[2]);
        }
    }
}
