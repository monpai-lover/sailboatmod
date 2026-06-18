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
        // maxChunkLoads=2048(已忽略,噪声采样零区块加载),nodesPerTick=128(网格对齐修好相遇后正常航线
        // 几千~万节点内即收敛,128/tick 分摊到多 tick 防卡顿;密度函数查询仍有成本但 snap 后总节点数大降),
        // chunkLoadsPerTick=32(已忽略), timeoutTicks=90s。
        return new WaterRoutePolicy(8, 2, 1, 3, 6144, 80000, 2048, 128, 32, 20 * 90);
    }

    /**
     * 船卡住自救专用:收紧搜索半径(只需脱离当前卡点附近重连航线,1500 格足够)+ 收紧节点预算,
     * 因为自救是<b>同步</b>跑(单 tick 内跑完),不能让它发散成几万节点卡主线程。其余同 defaults。
     */
    public static WaterRoutePolicy rescue() {
        return new WaterRoutePolicy(8, 2, 1, 3, 1500, 20000, 2048, 2048, 32, 20 * 90);
    }
}
