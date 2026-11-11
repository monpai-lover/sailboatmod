package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadPlannerPathfinderRunnerFactory {
    private RoadPlannerPathfinderRunnerFactory() {
    }

    /** 一次自动补全的总时限(后台线程);超时的窗口沿用粗路节点。 */
    public static final long AUTO_COMPLETE_BUDGET_MILLIS = 45_000L;

    public static RoadPlannerAutoCompleteService.PathfinderRunner serverRunner(ServerLevel level) {
        RoadPlannerAutoCompleteService service = serverService(level);
        return service == null ? null : service::runPathfinderOnly;
    }

    /** 旧签名:默认噪声精度(NORMAL)。 */
    public static RoadPlannerAutoCompleteService serverService(ServerLevel level) {
        return serverService(level, false);
    }

    /**
     * @param useRealChunk true = 真实区块高度图精度(SamplingPrecision.HIGH,AccurateHeightSampler,准、慢);
     *                     false = 噪声精度(NORMAL,FastHeightSampler,快、远处粗)。
     */
    public static RoadPlannerAutoCompleteService serverService(ServerLevel level, boolean useRealChunk) {
        if (level == null) {
            return null;
        }
        PathfindingConfig config = new PathfindingConfig();
        // 用全局默认算法(POTENTIAL_FIELD,势场+等高线代价,沿地形等高线走更自然)。
        // 之前这里硬编码 BIDIRECTIONAL_ASTAR 覆盖了默认 → 自动补全实际跑的是双向A*,非本意的 potential field。
        // 注意:potential field 同为 8 邻网格,仍会有离散锯齿,由下游 RoadRouteRefiner 拉直并圆角。
        config.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        // 寻路精度:玩家在道路规划器工具栏切换(真实区块 HIGH / 噪声 NORMAL)。
        config.setSamplingPrecision(useRealChunk
                ? PathfindingConfig.SamplingPrecision.HIGH
                : PathfindingConfig.SamplingPrecision.NORMAL);
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getSamplingPrecision()).withEntryLimit(65_536);
        // 远距离两城:粗步长找走向 + 分窗精寻,有总时限并响应中断(见 LongRangeRoadPathfinder)。只在后台线程运行。
        LongRangeRoadPathfinder pathfinder = new LongRangeRoadPathfinder(config);
        var lastPlan = new java.util.concurrent.atomic.AtomicReference<LongRangeRoadPathfinder.Result>();
        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            var result = pathfinder.findPath(from, destination, cache, AUTO_COMPLETE_BUDGET_MILLIS - 10_000L);
            lastPlan.set(result);
            return result.path();
        };
        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(cache, new RoadConfig().getBridge()),
                (x, z) -> !cache.isWater(x, z) || cache.getWaterDepth(x, z) <= 0,
                nodes -> RoadPlannerTerrainSampleCollector.collect(cache, nodes)
        ).withRouteTerrain(new com.monpai.sailboatmod.roadplanner.corridor.RoadRouteRefiner.Terrain() {
            @Override public int height(int x, int z) { return cache.getHeight(x, z); }
            @Override public boolean water(int x, int z) { return cache.isWater(x, z) && cache.getWaterDepth(x, z) > 0; }
            @Override public boolean blocked(int x, int z) { return cache.isBlocked(x, z); }
        }).withPlanningNote(() -> {
            var result = lastPlan.get();
            return result != null && result.refinedWindows() < result.windows()
                    ? "（部分区段为粗略预览，建造前需复核）" : "";
        });
    }
}
