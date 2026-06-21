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
     * 两段式起始/尾段「真实区块段」:step=4(快照范围只 96 格,step=4 够精细绕码头/运河、节点适中跑得快),
     * maxSearchRadius=256(覆盖快照半径 96 + 余量,goal 外推到快照边缘),maxExpandedNodes=20000,timeout=30s。
     * 配合 RealChunkRouteWorld 的近海偏好代价 + 泊位信任,从泊位驶出到快照边缘大洋衔接点(不再贴岸高精度)。
     */
    public static WaterRoutePolicy realChunkSegment() {
        return new WaterRoutePolicy(4, 2, 1, 3, 256, 20000, 0, 512, 0, 20 * 30);
    }

    /**
     * 三段式中段「大洋长距离」粗阶段:step=12 跨大洋(不取 16:群岛多窄缝/小岛,step 太大易跳过岛或找不到窄缝穿过;
     * biome 门槛已让小岛=陆地强制精判 blocked,segmentPassable 边插值 spacing=2 在 12 格内 6 点拦跨岛)。
     * maxSearchRadius=8192,maxExpandedNodes=160000(群岛绕行节点多),timeout=90s。精阶段沿粗路走廊 step=8 精寻。
     */
    public static WaterRoutePolicy longDistance() {
        // timeout 90→60s:绕陆修好后正常航线远不到上限,给「绕不出」退化情况更快失败(后台跑,不卡主线程)。
        return new WaterRoutePolicy(12, 2, 1, 3, 8192, 160000, 0, 128, 0, 20 * 60);
    }

    /** 中段精阶段(走廊内):step=8,沿粗路 ±走廊精寻,半径同长距离。 */
    public static WaterRoutePolicy longDistanceRefine() {
        return new WaterRoutePolicy(8, 2, 1, 3, 8192, 120000, 0, 128, 0, 20 * 60);
    }

    /**
     * 中段真实区块「接力绕行」段:穿陆处从好节点用真实快照高精度绕到下一个好节点。step=4 精细绕,
     * maxSearchRadius=256(覆盖单跳局部快照),maxExpandedNodes=8000(单跳预算,耗光则落水点接力),timeout=30s。
     */
    public static WaterRoutePolicy detourSegment() {
        return new WaterRoutePolicy(4, 2, 1, 3, 256, 8000, 0, 512, 0, 20 * 30);
    }

    /**
     * <b>NBT 两阶段·粗走廊阶段</b>(step=24 档,= {@link #nbtCoarseStep(int) nbtCoarseStep(24)}):大 step 粗网格跑
     * 大致走向,halfWidth=0 只求走向。窄海峡场景由调用方走 {@link #nbtCoarseStep} 渐进缩小 step 兜底。
     */
    public static WaterRoutePolicy nbtCoarse() {
        return nbtCoarseStep(24);
    }

    /**
     * <b>NBT 两阶段·走廊精寻阶段</b>:step=8 + halfWidth=1(船 3×3),沿粗路 ±走廊精寻。搜索空间被走廊收死 →
     * 双向 A* 不发散。maxSearchRadius 同粗阶段(走廊约束才是主闸门);maxExpandedNodes=160000(走廊内绕岛节点多)。
     */
    public static WaterRoutePolicy nbtRefine() {
        return new WaterRoutePolicy(8, 2, 1, 3, 12288, 160000, 0, 128, 0, 20 * 90);
    }

    /**
     * <b>NBT 粗走廊·渐进 step 版</b>(2026-06):{@link #nbtCoarse()} 固定 step=24 会<b>跳过窄海峡</b>——海峡只有
     * 1-8 格宽时,step=24 网格点没有任何一个落进海峡 → A* 没有「站在海峡里」的可航节点 → 起终水域在粗网格上不连通 →
     * 前后向开集都耗尽失败。改为<b>渐进 step</b>:先大 step 快试(路宽时省节点),失败逐级缩小(24→12→8→4)直到
     * 网格点能踩进窄海峡。本工厂按指定 step 派生 coarse policy:
     * <ul>
     *   <li><b>halfWidth=0</b>:只求水体连通走向,不卡船宽(1 格窄海峡也当通路),船宽交阶段二精寻。</li>
     *   <li><b>maxExpandedNodes 随 step 缩小放大</b>:step 越小同距离节点越多((24/step)² 倍),给足预算防提前耗尽
     *       误判不连通。step=4 时约 24²/4²×120000 ≈ 给到 600000。</li>
     * </ul>
     * 配 {@link RealBlockWaterWorld#coarse}(无约束全开 + 泊位信任,真实 NBT 判水,onDemand 前沿后台读)。
     */
    public static WaterRoutePolicy nbtCoarseStep(int step) {
        int s = Math.max(1, step);
        // 节点预算随 step 缩小按面积比放大(基准 step=24 给 120000),封顶 800000 防 step 过小预算爆炸。
        long scaled = 120000L * (24L * 24L) / ((long) s * s);
        int maxNodes = (int) Math.min(800000L, Math.max(120000L, scaled));
        return new WaterRoutePolicy(s, 2, 0, 3, 12288, maxNodes, 0, 256, 0, 20 * 60);
    }
}
