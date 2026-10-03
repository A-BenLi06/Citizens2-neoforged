package net.citizensnpcs.api.hpastar;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.Predicate;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import net.citizensnpcs.api.astar.pathfinder.BlockSource;
import net.citizensnpcs.api.astar.pathfinder.Path;
import net.citizensnpcs.api.util.BoundingBox;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

@Timeout(60)
class HPAGraphTest {
    @BeforeAll static void bootstrap() { Bootstrap.bootStrap(); }

    @Test void negativeHighAndSeparatedBy256LayersRemainDistinct() {
        Source source = new Source(-64, 320);
        for (int y : new int[] { -60, 196, 300 }) source.floor(0, 22, 0, 4, y - 1);
        HPAGraph graph = graph(source);
        for (int y : new int[] { -60, 196, 300 }) for (int depth = 0; depth <= HPAGraph.MAX_DEPTH; depth++) {
            List<BlockPos> path = route(graph, new BlockPos(2, y, 2), new BlockPos(20, y, 2), depth);
            assertEquals(18, cost(path), 0.0001);
            assertTrue(path.stream().allMatch(pos -> pos.getY() == y));
        }
        assertNotSame(graph.getClusterAt(0, new BlockPos(2, -60, 2)), graph.getClusterAt(0, new BlockPos(2, 196, 2)));
    }

    @Test void dimensionBoundsNeedNotContainZero() {
        for (int floor : new int[] { -150, 300 }) {
            Source source = new Source(floor - 7, floor + 17);
            source.floor(0, 22, 0, 4, floor);
            HPAGraph graph = graph(source);
            route(graph, new BlockPos(2, floor + 1, 2), new BlockPos(20, floor + 1, 2), HPAGraph.MAX_DEPTH);
            assertFalse(graph.walkable(2, source.minY, 2));
            assertFalse(graph.walkable(2, source.maxY - 1, 2));
        }
    }

    @Test void adjacentRegionsConnectInEitherOrderAtEveryAbstraction() {
        for (boolean reverse : new boolean[] { false, true }) {
            Source source = new Source(-8, 8);
            source.floor(-63, 140, 1, 3, -1);
            HPAGraph graph = new HPAGraph(source, 0, 0, 0);
            int[] regions = reverse ? new int[] { 128, 64, 0, -64 } : new int[] { -64, 0, 64, 128 };
            for (int x : regions) graph.addClusters(x, 0);
            assertTrue(graph.clusters.get(HPAGraph.MAX_DEPTH).stream().anyMatch(cluster -> !cluster.getNodes().isEmpty()));
            for (int depth = 0; depth <= HPAGraph.MAX_DEPTH; depth++) {
                List<BlockPos> path = route(graph, new BlockPos(-63, 0, 2), new BlockPos(130, 0, 2), depth);
                assertEquals(193, cost(path), 0.0001);
            }
            assertNotSame(graph.getClusterAt(0, new BlockPos(15, 0, 2)), graph.getClusterAt(0, new BlockPos(16, 0, 2)));
            assertSame(graph.getClusterAt(3, new BlockPos(0, 0, 2)), graph.getClusterAt(3, new BlockPos(127, 0, 2)));
            assertNotSame(graph.getClusterAt(3, new BlockPos(127, 0, 2)), graph.getClusterAt(3, new BlockPos(128, 0, 2)));
        }
    }

    @Test void repeatedQueriesOnExistingPortalsDoNotRemoveOrDuplicateThem() {
        Source source = new Source(-8, 8);
        source.floor(0, 70, 1, 3, -1);
        HPAGraph graph = new HPAGraph(source, 0, 0, 0);
        graph.addClusters(List.of(BlockPos.ZERO, new BlockPos(64, 0, 0)));
        List<Integer> before = sizes(graph);
        BlockPos start = new BlockPos(15, 0, 2), goal = new BlockPos(64, 0, 2);
        HPAGraphNode portal = graph.getClusterAt(0, start).getNode(start);
        assertNotNull(portal);
        for (int depth = 0; depth <= HPAGraph.MAX_DEPTH; depth++) for (int i = 0; i < 3; i++) {
            route(graph, start, goal, depth);
            assertEquals(List.of(start), graph.findPath(start, start, depth).getBlocks());
            assertEquals(before, sizes(graph));
            assertSame(portal, graph.getClusterAt(0, start).getNode(start));
        }
    }

    @Test void blockedCornersCannotBeCutWithinOrBetweenClusters() {
        for (int at : new int[] { 4, 14, 15 }) {
            Source source = new Source(-8, 8);
            source.floor(0, 20, 0, 20, -1);
            source.wall(at + 1, 0, at);
            source.wall(at, 0, at + 1);
            HPAGraph graph = graph(source);
            BlockPos from = new BlockPos(at, 0, at), to = new BlockPos(at + 1, 0, at + 1);
            assertFalse(graph.canMove(from, to));
            List<BlockPos> route = route(graph, from, to, HPAGraph.MAX_DEPTH);
            assertTrue(route.size() > 2);
            assertSafe(source, route);
        }
    }

    @Test void stairsCrossHorizontalAndVerticalClusterBoundariesTogether() {
        Source source = new Source(0, 32);
        for (int x = 14; x <= 18; x++) source.set(x, x - 9, 2, true);
        HPAGraph graph = graph(source);
        for (int depth = 0; depth <= HPAGraph.MAX_DEPTH; depth++) {
            List<BlockPos> path = route(graph, new BlockPos(14, 6, 2), new BlockPos(18, 10, 2), depth);
            assertEquals(5, path.size());
            assertSafe(source, path);
        }
    }

    @Test void twoWalkableEndpointsDoNotPermitSteppingThroughALowCeiling() {
        Source source = new Source(-8, 8);
        source.set(3, -1, 2, true);
        source.set(4, 0, 2, true);
        source.set(3, 2, 2, true);
        HPAGraph graph = graph(source);
        assertTrue(graph.walkable(3, 0, 2));
        assertTrue(graph.walkable(4, 1, 2));
        assertFalse(graph.canMove(new BlockPos(3, 0, 2), new BlockPos(4, 1, 2)));
        assertTrue(graph.findPath(new BlockPos(3, 0, 2), new BlockPos(4, 1, 2)).getBlocks().isEmpty());
    }

    @Test void missingBlockedAndUnreachableEndpointsReturnEmptyPaths() {
        Source source = new Source(-8, 8);
        source.floor(0, 32, 2, 2, -1);
        source.wall(15, 0, 2);
        HPAGraph graph = graph(source);
        for (BlockPos goal : List.of(new BlockPos(15, 0, 2), new BlockPos(24, 0, 2), new BlockPos(200, 0, 2),
                new BlockPos(1, -8, 2), new BlockPos(1, 7, 2))) {
            Path path = graph.findPath(new BlockPos(2, 0, 2), goal);
            assertTrue(path.getBlocks().isEmpty());
            assertTrue(path.isComplete());
        }
    }

    @Test void unknownCornerDataCannotBeTreatedAsKnownAir() {
        Source source = new Source(-8, 8);
        source.floor(0, 32, 0, 32, -1);
        source.unavailable = pos -> pos.getX() == 16 && pos.getZ() == 15;
        HPAGraph graph = graph(source);
        assertFalse(graph.canMove(new BlockPos(15, 0, 15), new BlockPos(16, 0, 16)));
    }

    @Test void failedRefinementDoesNotInventAStraightPathAndDirtyRebuildRecovers() {
        Source source = new Source(-8, 8);
        source.floor(0, 32, 2, 2, -1);
        HPAGraph graph = graph(source);
        BlockPos start = new BlockPos(2, 0, 2), goal = new BlockPos(24, 0, 2);
        route(graph, start, goal, HPAGraph.MAX_DEPTH);
        source.wall(8, 0, 2);
        assertTrue(graph.findPath(start, goal).getBlocks().isEmpty());
        graph.markDirtyBlock(8, 2);
        assertTrue(graph.findPath(start, goal).getBlocks().isEmpty());
        source.set(8, 0, 2, false);
        source.set(8, 1, 2, false);
        graph.invalidateRegion(8, 2);
        route(graph, start, goal, HPAGraph.MAX_DEPTH);
        source.wall(16, 0, 2);
        assertTrue(graph.findPath(start, goal).getBlocks().isEmpty());
    }

    @Test void dirtyChunkIncludesEveryOverlappingUnalignedRegion() {
        Source source = new Source(-8, 8);
        source.floor(0, 30, 0, 30, -1);
        HPAGraph graph = new HPAGraph(source, 3, 0, 5);
        graph.addClusters(3, 5);
        BlockPos start = new BlockPos(0, 0, 0), goal = new BlockPos(2, 0, 2);
        assertTrue(graph.findPath(start, goal).getBlocks().isEmpty());
        graph.markDirtyChunk(0, 0);
        route(graph, start, goal, HPAGraph.MAX_DEPTH);
    }

    @Test void interruptedBuildCanBeRetriedWithoutPartialClusters() {
        Source source = new Source(-8, 8);
        source.floor(0, 20, 1, 3, -1);
        HPAGraph graph = new HPAGraph(source, 0, 0, 0);
        source.onRead = () -> { source.onRead = null; Thread.currentThread().interrupt(); };
        try { assertThrows(CancellationException.class, () -> graph.addClusters(0, 0)); }
        finally { Thread.interrupted(); }
        graph.addClusters(0, 0);
        route(graph, new BlockPos(2, 0, 2), new BlockPos(18, 0, 2), HPAGraph.MAX_DEPTH);
    }

    @Test void interruptionDuringRefinementCleansEveryTemporaryMembership() {
        Source source = new Source(-8, 8);
        source.floor(0, 32, 1, 3, -1);
        HPAGraph graph = graph(source);
        List<Integer> before = sizes(graph);
        int topCount = graph.clusters.get(HPAGraph.MAX_DEPTH).stream().mapToInt(cluster -> cluster.getNodes().size()).sum();
        source.onRead = () -> {
            if (graph.clusters.get(HPAGraph.MAX_DEPTH).stream().mapToInt(cluster -> cluster.getNodes().size()).sum() > topCount) {
                source.onRead = null;
                Thread.currentThread().interrupt();
            }
        };
        try { assertThrows(CancellationException.class, () -> graph.findPath(new BlockPos(2, 0, 2), new BlockPos(24, 0, 2))); }
        finally { Thread.interrupted(); }
        assertNull(source.onRead, "Interruption happened after upper-level query insertion");
        assertEquals(before, sizes(graph));
        route(graph, new BlockPos(2, 0, 2), new BlockPos(24, 0, 2), HPAGraph.MAX_DEPTH);
        assertEquals(before, sizes(graph));
    }

    @Test void abstractAStarSettlesTheGoalInsteadOfReturningWhenGenerated() {
        HPAGraph graph = new HPAGraph(new Source(-8, 8), 0, 0, 0);
        HPAGraphNode from = new HPAGraphNode(0, 0, 0), middle = new HPAGraphNode(1, 0, 0), to = new HPAGraphNode(2, 0, 0);
        from.connect(0, to, HPAGraphEdge.EdgeType.INTRA, 10);
        from.connect(0, middle, HPAGraphEdge.EdgeType.INTRA, 1);
        middle.connect(0, to, HPAGraphEdge.EdgeType.INTRA, 1);
        assertEquals(2, graph.pathfind(from, to, 0).getCost());
    }

    @Test void parentEdgesCannotUseRoutesOutsideTheirFootprint() {
        Source source = new Source(-8, 8);
        source.floor(0, 48, 0, 20, -1);
        for (int x = 0; x < 32; x++) source.wall(x, 0, 8);
        HPAGraph graph = graph(source);
        HPACluster parent = graph.getClusterAt(1, new BlockPos(31, 0, 2));
        HPAGraphNode from = parent.getNode(new BlockPos(31, 0, 2)), to = parent.getNode(new BlockPos(31, 0, 14));
        assertNotNull(from);
        assertNotNull(to);
        assertTrue(from.getEdges(1).stream().noneMatch(edge -> edge.type == HPAGraphEdge.EdgeType.INTRA && edge.to.equals(to)));
        List<BlockPos> path = route(graph, new BlockPos(2, 0, 2), new BlockPos(2, 0, 14), HPAGraph.MAX_DEPTH);
        assertTrue(path.stream().anyMatch(pos -> pos.getX() >= 32));
        assertSafe(source, path);
    }

    @Test void nodeCoordinatesAndDistanceDoNotOverflowOldPackedFields() {
        HPAGraphNode a = new HPAGraphNode(-30_000_000, -60, 30_000_000);
        HPAGraphNode b = new HPAGraphNode(30_000_000, 300, -30_000_000);
        assertTrue(Double.isFinite(a.distance(b)) && a.distance(b) > 60_000_000);
        assertNotEquals(new HPAGraphNode(1, -60, 2), new HPAGraphNode(1, 196, 2));
    }

    private static HPAGraph graph(Source source) {
        HPAGraph graph = new HPAGraph(source, 0, 0, 0);
        graph.addClusters(0, 0);
        return graph;
    }

    private static List<BlockPos> route(HPAGraph graph, BlockPos start, BlockPos goal, int depth) {
        List<BlockPos> path = graph.findPath(start, goal, depth).getBlocks();
        assertFalse(path.isEmpty(), () -> start + " -> " + goal + " at level " + depth);
        assertEquals(start, path.getFirst());
        assertEquals(goal, path.getLast());
        for (int i = 1; i < path.size(); i++) assertTrue(graph.canMove(path.get(i - 1), path.get(i)), path.toString());
        return path;
    }

    private static List<Integer> sizes(HPAGraph graph) {
        List<Integer> sizes = new ArrayList<>();
        for (int level = 0; level < graph.clusters.size(); level++) {
            int nodes = 0, edges = 0;
            for (HPACluster cluster : graph.clusters.get(level)) for (HPAGraphNode node : cluster.getNodes()) {
                nodes++;
                edges += node.getEdges(level).size();
            }
            sizes.add(nodes);
            sizes.add(edges);
        }
        return sizes;
    }

    private static double cost(List<BlockPos> path) {
        double cost = 0;
        for (int i = 1; i < path.size(); i++) cost += Math.sqrt(path.get(i - 1).distSqr(path.get(i)));
        return cost;
    }

    private static void assertSafe(Source source, List<BlockPos> path) {
        for (int i = 0; i < path.size(); i++) {
            BlockPos point = path.get(i);
            assertTrue(source.getBlockAt(point).isAir() && source.getBlockAt(point.above()).isAir());
            assertTrue(source.getBlockAt(point.below()).is(Blocks.STONE));
            if (i == 0) continue;
            BlockPos last = path.get(i - 1);
            assertTrue(Math.abs(last.getX() - point.getX()) <= 1 && Math.abs(last.getY() - point.getY()) <= 1
                    && Math.abs(last.getZ() - point.getZ()) <= 1);
            int high = Math.max(last.getY(), point.getY());
            for (int x : new int[] { last.getX(), point.getX() }) for (int z : new int[] { last.getZ(), point.getZ() }) {
                assertTrue(source.getBlockAt(x, high, z).isAir() && source.getBlockAt(x, high + 1, z).isAir());
            }
        }
    }

    private static final class Source extends BlockSource implements LevelHeightAccessor {
        final int minY, maxY;
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        Runnable onRead;
        Predicate<BlockPos> unavailable = pos -> false;
        Source(int minY, int maxY) { this.minY = minY; this.maxY = maxY; }
        void set(int x, int y, int z, boolean solid) { blocks.put(new BlockPos(x, y, z), (solid ? Blocks.STONE : Blocks.AIR).defaultBlockState()); }
        void wall(int x, int y, int z) { set(x, y, z, true); set(x, y + 1, z, true); }
        void floor(int minX, int maxX, int minZ, int maxZ, int y) {
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) set(x, y, z, true);
        }
        @Override public BlockState getBlockAt(int x, int y, int z) {
            if (onRead != null) onRead.run();
            return blocks.getOrDefault(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState());
        }
        @Override public boolean isBlockAvailable(int x, int y, int z) { return isYWithinBounds(y) && !unavailable.test(new BlockPos(x, y, z)); }
        @Override public BoundingBox getCollisionBox(int x, int y, int z) { return new BoundingBox(x, y, z, x + 1, y + 1, z + 1); }
        @Override public boolean isYWithinBounds(int y) { return y >= minY && y < maxY; }
        @Override public int getHeight() { return maxY - minY; }
        @Override public int getMinBuildHeight() { return minY; }
    }
}
