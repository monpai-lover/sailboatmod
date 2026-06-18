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

    /**
     * 三段式起始/尾段「贴岸高精度」:step=2 精细贴岸,maxSearchRadius=160(略大于真实快照半径 96,够绕海湾),
     * maxExpandedNodes=20000(贴岸距离短),timeout=30s。配合 RealChunkRouteWorld 的贴岸代价沿海岸驶出/进港。
     */
    public static WaterRoutePolicy coastalHighPrecision() {
        return new WaterRoutePolicy(2, 2, 1, 3, 160, 20000, 0, 512, 0, 20 * 30);
    }

    /**
     * 三段式中段「大洋长距离」粗阶段:step=12 跨大洋(不取 16:群岛多窄缝/小岛,step 太大易跳过岛或找不到窄缝穿过;
     * biome 门槛已让小岛=陆地强制精判 blocked,segmentPassable 边插值 spacing=2 在 12 格内 6 点拦跨岛)。
     * maxSearchRadius=8192,maxExpandedNodes=160000(群岛绕行节点多),timeout=90s。精阶段沿粗路走廊 step=8 精寻。
     */
    public static WaterRoutePolicy longDistance() {
        return new WaterRoutePolicy(12, 2, 1, 3, 8192, 160000, 0, 128, 0, 20 * 90);
    }

    /** 中段精阶段(走廊内):step=8,沿粗路 ±走廊精寻,半径同长距离。 */
    public static WaterRoutePolicy longDistanceRefine() {
        return new WaterRoutePolicy(8, 2, 1, 3, 8192, 120000, 0, 128, 0, 20 * 90);
    }
}
