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
        // stepSize=8, berthSearchStep=2, boatHalfWidth=1(船按 3×3 算,放宽泊位+窄海峡通行;原 5×5 太严岸边凑不齐),
        // clearanceHeight=3, maxSearchRadius=16384(外层防失控护栏,不再当主距离闸门),
        // maxExpandedNodes=80000, maxChunkLoads=16384(原4096对~1900m跨海峡航线不够,CHUNK_BUDGET_EXCEEDED),
        // nodesPerTick=256, chunkLoadsPerTick=32(原8太慢,加快长航线寻路防超时), timeoutTicks=90s。
        return new WaterRoutePolicy(8, 2, 1, 3, 16384, 80000, 16384, 256, 32, 20 * 90);
    }
}
