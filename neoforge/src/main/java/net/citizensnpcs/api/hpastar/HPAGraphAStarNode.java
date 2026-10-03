package net.citizensnpcs.api.hpastar;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HPAGraphAStarNode implements Comparable<HPAGraphAStarNode> {
    private final HPAGraphEdge edge;
    float g, h;
    final HPAGraphNode node;
    HPAGraphAStarNode parent;

    public HPAGraphAStarNode(HPAGraphNode node, HPAGraphEdge edge) {
        this.node = node;
        this.edge = edge;
    }

    @Override
    public int compareTo(HPAGraphAStarNode other) {
        return Float.compare(g + h, other.g + other.h);
    }

    HPAGraphEdge getEdge() {
        return edge;
    }

    public List<HPAGraphAStarNode> reconstructSolution() {
        List<HPAGraphAStarNode> result = new ArrayList<>();
        for (HPAGraphAStarNode current = this; current != null; current = current.parent) result.add(current);
        Collections.reverse(result);
        return result;
    }

    @Override
    public String toString() {
        return (edge == null ? "" : edge.from.toString()) + "->" + node;
    }
}
