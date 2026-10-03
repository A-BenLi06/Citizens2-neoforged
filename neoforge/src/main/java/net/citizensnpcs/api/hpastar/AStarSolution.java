package net.citizensnpcs.api.hpastar;

import java.util.Collection;
import java.util.List;

import net.minecraft.world.phys.Vec3;

public class AStarSolution {
    final float cost;
    final List<HPAGraphAStarNode> path;

    public AStarSolution(List<HPAGraphAStarNode> path, float cost) {
        this.path = path == null ? List.of() : List.copyOf(path);
        this.cost = cost;
    }

    public Collection<Vec3> convertToVectors() {
        return path.stream().map(node -> node.node.toVector()).toList();
    }

    public float getCost() {
        return cost;
    }
}
