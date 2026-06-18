package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

public final class RoadPlannerPathfinderRunnerFactory {
    private RoadPlannerPathfinderRunnerFactory() {
    }

    public static RoadPlannerAutoCompleteService.PathfinderRunner serverRunner(ServerLevel level) {
        RoadPlannerAutoCompleteService service = serverService(level);
        return service == null ? null : service::runPathfinderOnly;
    }

    public static RoadPlannerAutoCompleteService serverService(ServerLevel level) {
        if (level == null) {
            return null;
        }
        PathfindingConfig config = new PathfindingConfig();
        // 用全局默认算法(POTENTIAL_FIELD,势场+等高线代价,沿地形等高线走更自然)。
        // 之前这里硬编码 BIDIRECTIONAL_ASTAR 覆盖了默认 → 自动补全实际跑的是双向A*,非本意的 potential field。
        // 注意:potential field 同为 8 邻网格,仍会有离散锯齿,锯齿由下游 PathSmoother 样条平滑抹掉。
        config.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        Pathfinder pathfinder = PathfinderFactory.create(config);
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getSamplingPrecision());
        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            PathResult result = pathfinder.findPath(from, destination, cache);
            return result.success() ? result.path() : List.of();
        };
        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(cache, new RoadConfig().getBridge()),
                (x, z) -> !cache.isWater(x, z) || cache.getWaterDepth(x, z) <= 0,
                nodes -> RoadPlannerTerrainSampleCollector.collect(cache, nodes)
        );
    }
}
