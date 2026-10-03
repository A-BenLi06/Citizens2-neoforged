package net.citizensnpcs.api.hpastar;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

public class HPACluster {
    final int clusterSize, clusterHeight, clusterX, clusterY, clusterZ;
    private final HPAGraph graph;
    private final int level;
    private final Map<BlockPos, HPAGraphNode> nodesByPosition = new LinkedHashMap<>();
    private final List<HPAGraphNode> nodes = new ArrayList<>();
    private final byte[] walkableCache, clearanceCache;

    public HPACluster(HPAGraph graph, int level, int clusterSize, int clusterHeight, int clusterX, int clusterY, int clusterZ) {
        if (level < 0 || clusterSize <= 0 || clusterHeight <= 0) throw new IllegalArgumentException();
        this.graph = graph;
        this.level = level;
        this.clusterSize = clusterSize;
        this.clusterHeight = clusterHeight;
        this.clusterX = clusterX;
        this.clusterY = clusterY;
        this.clusterZ = clusterZ;
        // Upper levels search the lower graph; allocating a block volume there wastes most of the graph's memory.
        int volume = level == 0 ? Math.multiplyExact(Math.multiplyExact(clusterSize, clusterSize), clusterHeight) : 0;
        walkableCache = new byte[volume];
        clearanceCache = new byte[volume];
    }

    public void buildFrom(List<HPACluster> children) {
        for (HPACluster child : children) {
            HPAGraph.checkInterrupted();
            if (!contains(child)) throw new IllegalArgumentException("Child crosses parent cluster boundary");
            for (HPAGraphNode node : child.nodes) {
                for (HPAGraphEdge edge : node.getEdges(level - 1)) {
                    if (edge.type != HPAGraphEdge.EdgeType.INTER || containsPoint(edge.to.x, edge.to.y, edge.to.z)) continue;
                    addReference(node);
                    node.connect(level, edge.to, HPAGraphEdge.EdgeType.INTER, edge.weight);
                }
            }
        }
        connectIntra();
    }

    /** Retains all valid crossings, including steps through vertical and diagonal cluster boundaries. */
    void connectBoundary(HPACluster other) {
        HPAEntrance boundary = new HPAEntrance(this, other);
        for (int y = boundary.minY; y <= boundary.maxY; y++) {
            HPAGraph.checkInterrupted();
            for (int x = boundary.minX; x <= boundary.maxX; x++) {
                for (int z = boundary.minZ; z <= boundary.maxZ; z++) {
                    if (!walkable(x, y, z)) continue;
                    for (int i = 0; i < HPAGraph.NEIGHBOURS.length; i++) {
                        int[] offset = HPAGraph.NEIGHBOURS[i];
                        int nx = x + offset[0], ny = y + offset[1], nz = z + offset[2];
                        if (!other.containsPoint(nx, ny, nz) || !other.walkable(nx, ny, nz)
                                || !graph.canMove(x, y, z, nx, ny, nz, this)) continue;
                        HPAGraphNode from = getOrAddNode(x, y, z), to = other.getOrAddNode(nx, ny, nz);
                        from.connect(level, to, HPAGraphEdge.EdgeType.INTER, HPAGraph.MOVE_COSTS[i]);
                    }
                }
            }
        }
    }

    public void connectIntra() {
        for (int i = 0; i < nodes.size(); i++) {
            HPAGraph.checkInterrupted();
            connectFrom(nodes.get(i), nodes.subList(i + 1, nodes.size()));
        }
    }

    private void connectFrom(HPAGraphNode from, List<HPAGraphNode> targets) {
        if (targets.isEmpty()) return;
        if (level == 0) {
            Map<Integer, HPAGraphNode> remaining = new HashMap<>();
            for (HPAGraphNode node : targets) remaining.put(index(node.x, node.y, node.z), node);
            searchBlocks(from, remaining, false);
        } else {
            connectLowerLevel(from, targets);
        }
    }

    private void connectLowerLevel(HPAGraphNode from, List<HPAGraphNode> targets) {
        Map<BlockPos, HPAGraphNode> remaining = new HashMap<>();
        for (HPAGraphNode node : targets) remaining.put(node.position, node);
        Map<HPAGraphNode, Float> costs = new HashMap<>();
        PriorityQueue<GraphFrontier> frontier = new PriorityQueue<>();
        costs.put(from, 0F);
        frontier.add(new GraphFrontier(from, 0));
        while (!frontier.isEmpty() && !remaining.isEmpty()) {
            HPAGraph.checkInterrupted();
            GraphFrontier current = frontier.remove();
            if (current.cost > costs.getOrDefault(current.node, Float.POSITIVE_INFINITY)) continue;
            HPAGraphNode reached = remaining.remove(current.node.position);
            if (reached != null) from.connect(level, reached, HPAGraphEdge.EdgeType.INTRA, current.cost);
            for (HPAGraphEdge edge : current.node.getEdges(level - 1)) {
                // A parent edge must never use a shortcut outside the parent's footprint.
                if (!containsPoint(edge.to.x, edge.to.y, edge.to.z)) continue;
                float cost = current.cost + edge.weight;
                if (cost >= costs.getOrDefault(edge.to, Float.POSITIVE_INFINITY)) continue;
                costs.put(edge.to, cost);
                frontier.add(new GraphFrontier(edge.to, cost));
            }
        }
    }

    private List<Vec3> searchBlocks(HPAGraphNode from, Map<Integer, HPAGraphNode> remaining, boolean refining) {
        float[] costs = new float[walkableCache.length];
        Arrays.fill(costs, Float.POSITIVE_INFINITY);
        int[] parents = refining ? new int[costs.length] : null;
        if (parents != null) Arrays.fill(parents, -1);
        PriorityQueue<BlockFrontier> frontier = new PriorityQueue<>();
        int startIndex = index(from.x, from.y, from.z);
        costs[startIndex] = 0;
        frontier.add(new BlockFrontier(startIndex, 0));
        while (!frontier.isEmpty() && !remaining.isEmpty()) {
            HPAGraph.checkInterrupted();
            BlockFrontier current = frontier.remove();
            if (current.cost > costs[current.index]) continue;
            HPAGraphNode reached = remaining.remove(current.index);
            if (reached != null) {
                if (refining) {
                    List<Vec3> path = new ArrayList<>();
                    for (int at = current.index; at != -1; at = parents[at]) path.add(position(at));
                    Collections.reverse(path);
                    return path;
                }
                from.connect(level, reached, HPAGraphEdge.EdgeType.INTRA, current.cost);
                if (remaining.isEmpty()) break;
            }
            int x = clusterX + current.index % clusterSize;
            int y = clusterY + current.index / (clusterSize * clusterSize);
            int z = clusterZ + current.index / clusterSize % clusterSize;
            for (int i = 0; i < HPAGraph.NEIGHBOURS.length; i++) {
                int[] offset = HPAGraph.NEIGHBOURS[i];
                int nx = x + offset[0], ny = y + offset[1], nz = z + offset[2];
                if (!containsPoint(nx, ny, nz)) continue;
                int nextIndex = index(nx, ny, nz);
                float cost = current.cost + HPAGraph.MOVE_COSTS[i];
                if (cost >= costs[nextIndex] || !graph.canMove(x, y, z, nx, ny, nz, refining ? null : this)) continue;
                costs[nextIndex] = cost;
                if (parents != null) parents[nextIndex] = current.index;
                frontier.add(new BlockFrontier(nextIndex, cost));
            }
        }
        return List.of();
    }

    List<Vec3> refine(HPAGraphNode from, HPAGraphNode to) {
        if (level != 0 || !containsPoint(from.x, from.y, from.z) || !containsPoint(to.x, to.y, to.z)
                || !graph.walkable(from.x, from.y, from.z) || !graph.walkable(to.x, to.y, to.z)) return List.of();
        Map<Integer, HPAGraphNode> target = new HashMap<>();
        target.put(index(to.x, to.y, to.z), to);
        return searchBlocks(from, target, true);
    }

    private int index(int x, int y, int z) {
        return ((y - clusterY) * clusterSize + z - clusterZ) * clusterSize + x - clusterX;
    }

    private Vec3 position(int index) {
        return new Vec3(clusterX + index % clusterSize, clusterY + index / (clusterSize * clusterSize),
                clusterZ + index / clusterSize % clusterSize);
    }

    public boolean contains(HPACluster child) {
        return child.clusterX >= clusterX && child.clusterY >= clusterY && child.clusterZ >= clusterZ
                && (long) child.clusterX + child.clusterSize <= (long) clusterX + clusterSize
                && (long) child.clusterY + child.clusterHeight <= (long) clusterY + clusterHeight
                && (long) child.clusterZ + child.clusterSize <= (long) clusterZ + clusterSize;
    }

    public boolean containsPoint(int x, int y, int z) {
        return x >= clusterX && x < (long) clusterX + clusterSize && y >= clusterY && y < (long) clusterY + clusterHeight
                && z >= clusterZ && z < (long) clusterZ + clusterSize;
    }

    public List<HPAGraphNode> getNodes() {
        return List.copyOf(nodes);
    }

    HPAGraphNode getNode(BlockPos position) {
        return nodesByPosition.get(position);
    }

    private HPAGraphNode getOrAddNode(int x, int y, int z) {
        HPAGraphNode existing = nodesByPosition.get(new BlockPos(x, y, z));
        if (existing != null) return existing;
        HPAGraphNode created = new HPAGraphNode(x, y, z);
        addReference(created);
        return created;
    }

    private void addReference(HPAGraphNode node) {
        HPAGraphNode existing = nodesByPosition.putIfAbsent(node.position, node);
        if (existing != null && existing != node) throw new IllegalArgumentException("Duplicate portal position");
        if (existing == null) nodes.add(node);
    }

    public boolean hasWalkableNodes() {
        for (int y = 0; y < clusterHeight; y++) {
            HPAGraph.checkInterrupted();
            for (int x = 0; x < clusterSize; x++) for (int z = 0; z < clusterSize; z++) {
                if (walkable(clusterX + x, clusterY + y, clusterZ + z)) return true;
            }
        }
        return false;
    }

    public void insert(HPAGraphNode node) {
        if (!containsPoint(node.x, node.y, node.z)) throw new IllegalArgumentException("Query node outside cluster");
        if (getNode(node.position) == node) return;
        addReference(node);
        connectFrom(node, nodes.stream().filter(other -> other != node).toList());
    }

    boolean walkable(int x, int y, int z) {
        if (level != 0 || !containsPoint(x, y, z)) return graph.walkable(x, y, z);
        int index = index(x, y, z);
        if (walkableCache[index] == 0) walkableCache[index] = (byte) (graph.walkable(x, y, z) ? 1 : 2);
        return walkableCache[index] == 1;
    }

    boolean clearColumn(int x, int y, int z) {
        if (level != 0 || !containsPoint(x, y, z)) return graph.clearColumn(x, y, z);
        int index = index(x, y, z);
        if (clearanceCache[index] == 0) clearanceCache[index] = (byte) (graph.clearColumn(x, y, z) ? 1 : 2);
        return clearanceCache[index] == 1;
    }

    /** Removes only membership and edges at this level; the same portal may belong to other levels. */
    public void remove(HPAGraphNode... removed) {
        for (HPAGraphNode node : removed) {
            if (nodesByPosition.get(node.position) != node) continue;
            if (node.edges.size() > level) {
                for (HPAGraphEdge edge : node.edges.get(level)) {
                    if (edge.to.edges.size() > level) edge.to.edges.get(level).removeIf(reverse -> reverse.to == node);
                }
                node.edges.get(level).clear();
            }
            nodesByPosition.remove(node.position);
            nodes.removeIf(other -> other == node);
        }
    }

    @Override
    public String toString() {
        return "C[" + level + "] (" + clusterX + "," + clusterY + "," + clusterZ + ")->("
                + ((long) clusterX + clusterSize - 1) + "," + ((long) clusterY + clusterHeight - 1) + ","
                + ((long) clusterZ + clusterSize - 1) + ")";
    }

    private record BlockFrontier(int index, float cost) implements Comparable<BlockFrontier> {
        @Override public int compareTo(BlockFrontier other) { return Float.compare(cost, other.cost); }
    }

    private record GraphFrontier(HPAGraphNode node, float cost) implements Comparable<GraphFrontier> {
        @Override public int compareTo(GraphFrontier other) { return Float.compare(cost, other.cost); }
    }
}
