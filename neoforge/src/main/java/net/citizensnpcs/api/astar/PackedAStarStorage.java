package net.citizensnpcs.api.astar;

import java.util.PriorityQueue;
import java.util.Queue;
import java.util.function.Supplier;

import it.unimi.dsi.fastutil.longs.Long2FloatOpenHashMap;
import net.citizensnpcs.api.astar.pathfinder.VectorNode;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * The storage the block pathfinder uses: a {@link PriorityQueue} frontier, and open/closed sets keyed by the node
 * position packed into a single {@code long}.
 * <p>
 * {@link SimpleAStarStorage} keys its maps by the node object, which means hashing a {@link VectorNode} and boxing a
 * {@link Float} for every one of the twenty-six neighbours of every expanded node. Packing the position instead lets the
 * whole open and closed set live in two primitive maps. The packing is vanilla's own
 * {@link BlockPos#asLong} layout — 26 bits of x, 26 of z, 12 of y — rather than upstream's hand-rolled shifts, which are
 * the same layout written out by hand.
 * <p>
 * Two deliberate differences from upstream. It reads a missing key as the map default of {@code 0} and so cannot tell an
 * absent node from one whose cost really is zero; {@code containsKey} is used here instead, which costs nothing and
 * removes the trap. And its re-open threshold is kept: a node already seen is only reconsidered when the new route to it
 * is better by more than one, which stops the search churning over routes that are equivalent in practice.
 */
public class PackedAStarStorage implements AStarStorage {
    private final Long2FloatOpenHashMap closed = new Long2FloatOpenHashMap();
    private final Long2FloatOpenHashMap open = new Long2FloatOpenHashMap();
    private final Queue<AStarNode> queue = new PriorityQueue<>(128);

    @Override
    public void close(AStarNode node) {
        long key = packPosition((VectorNode) node);
        open.remove(key);
        closed.put(key, node.g);
    }

    @Override
    public AStarNode getBestNode() {
        return queue.peek();
    }

    @Override
    public void open(AStarNode node) {
        long key = packPosition((VectorNode) node);
        queue.offer(node);
        open.put(key, node.g);
        closed.remove(key);
    }

    @Override
    public AStarNode removeBestNode() {
        return queue.poll();
    }

    @Override
    public boolean shouldExamine(AStarNode node) {
        long key = packPosition((VectorNode) node);
        boolean isOpen = open.containsKey(key);
        if (isOpen && open.get(key) - IMPROVEMENT_REWEIGHT_THRESHOLD > node.g) {
            open.remove(key);
            isOpen = false;
        }
        boolean isClosed = closed.containsKey(key);
        if (isClosed && closed.get(key) - IMPROVEMENT_REWEIGHT_THRESHOLD > node.g) {
            closed.remove(key);
            isClosed = false;
        }
        return !isOpen && !isClosed;
    }

    @Override
    public String toString() {
        return "PackedAStarStorage [closed=" + closed.size() + ", open=" + open.size() + "]";
    }

    private static long packPosition(VectorNode node) {
        Vec3 vector = node.getVector();
        return BlockPos.asLong(Mth.floor(vector.x), Mth.floor(vector.y), Mth.floor(vector.z));
    }

    public static final Supplier<AStarStorage> FACTORY = PackedAStarStorage::new;
    private static final float IMPROVEMENT_REWEIGHT_THRESHOLD = 1;
}
