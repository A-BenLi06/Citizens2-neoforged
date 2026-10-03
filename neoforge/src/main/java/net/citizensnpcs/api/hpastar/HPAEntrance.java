package net.citizensnpcs.api.hpastar;

/** The inclusive source-side boundary of two adjacent clusters. */
public class HPAEntrance {
    final int minX, minY, minZ, maxX, maxY, maxZ;

    HPAEntrance(HPACluster from, HPACluster to) {
        minX = Math.max(from.clusterX, to.clusterX - 1);
        minY = Math.max(from.clusterY, to.clusterY - 1);
        minZ = Math.max(from.clusterZ, to.clusterZ - 1);
        maxX = Math.min(from.clusterX + from.clusterSize - 1, to.clusterX + to.clusterSize);
        maxY = Math.min(from.clusterY + from.clusterHeight - 1, to.clusterY + to.clusterHeight);
        maxZ = Math.min(from.clusterZ + from.clusterSize - 1, to.clusterZ + to.clusterSize);
    }
}
