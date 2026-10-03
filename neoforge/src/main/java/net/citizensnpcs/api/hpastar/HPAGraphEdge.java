package net.citizensnpcs.api.hpastar;

public class HPAGraphEdge {
    final HPAGraphNode from, to;
    final EdgeType type;
    final float weight;

    public HPAGraphEdge(HPAGraphNode from, HPAGraphNode to, EdgeType type, float weight) {
        this.from = from;
        this.to = to;
        this.type = type;
        this.weight = weight;
    }

    public enum EdgeType { INTER, INTRA }
}
