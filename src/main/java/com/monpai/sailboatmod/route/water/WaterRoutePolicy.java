package com.monpai.sailboatmod.route.water;

public record WaterRoutePolicy(int stepSize,
                               int berthSearchStep,
                               int boatHalfWidth,
                               int clearanceHeight,
                               int maxSearchRadius,
                               int maxExpandedNodes,
                               int maxChunkLoads,
                               int nodesPerTick,
                               int chunkLoadsPerTick,
                               int timeoutTicks) {
    public static WaterRoutePolicy defaults() {
        // stepSize=8, berthSearchStep=2, boatHalfWidth=1(船 3×3), clearanceHeight=3(=最小水深),
        // maxSearchRadius=6144(双向A*防发散,噪声采样后不再当主闸门),maxExpandedNodes=80000,
        // maxChunkLoads=2048(噪声采样寻路零区块加载,仅留兜底),nodesPerTick=512(噪声采样快,加快出航线),
        // chunkLoadsPerTick=32(已忽略), timeoutTicks=90s。
        return new WaterRoutePolicy(8, 2, 1, 3, 6144, 80000, 2048, 512, 32, 20 * 90);
    }
}
