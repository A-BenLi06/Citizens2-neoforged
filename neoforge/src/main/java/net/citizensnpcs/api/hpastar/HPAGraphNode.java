package net.citizensnpcs.api.hpastar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/** A portal shared by all abstraction levels that contain this position. */
public class HPAGraphNode {
    final List<List<HPAGraphEdge>> edges = new ArrayList<>();
    final int x, y, z;
    final BlockPos position;

    public HPAGraphNode(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
        position = new BlockPos(x, y, z);
    }

    public void connect(int level, HPAGraphNode to, HPAGraphEdge.EdgeType type, float weight) {
        if (level < 0 || !Float.isFinite(weight) || weight < 0) throw new IllegalArgumentException();
        if (equals(to)) return;
        connectOneWay(level, to, type, weight);
        to.connectOneWay(level, this, type, weight);
    }

    private void connectOneWay(int level, HPAGraphNode to, HPAGraphEdge.EdgeType type, float weight) {
        while (edges.size() <= level) edges.add(new ArrayList<>());
        List<HPAGraphEdge> atLevel = edges.get(level);
        for (int i = 0; i < atLevel.size(); i++) {
            HPAGraphEdge old = atLevel.get(i);
            if (old.to.equals(to) && old.type == type) {
                if (weight < old.weight) atLevel.set(i, new HPAGraphEdge(this, to, type, weight));
                return;
            }
        }
        atLevel.add(new HPAGraphEdge(this, to, type, weight));
    }

    public double distance(HPAGraphNode dest) {
        double dx = (double) x - dest.x, dy = (double) y - dest.y, dz = (double) z - dest.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HPAGraphNode node && position.equals(node.position);
    }

    public List<HPAGraphEdge> getEdges(int level) {
        return level < 0 || level >= edges.size() ? Collections.emptyList() : Collections.unmodifiableList(edges.get(level));
    }

    @Override
    public int hashCode() {
        return position.hashCode();
    }

    @Override
    public String toString() {
        return x + "," + y + "," + z;
    }

    public Vec3 toVector() {
        return new Vec3(x, y, z);
    }
}
