package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMask;
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
        config.setAlgorithm(PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
        Pathfinder pathfinder = PathfinderFactory.create(config);
        RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
        TerrainSamplingCache terrainCache = new TerrainSamplingCache(level, config.getSamplingPrecision());
        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            RoadPlannerObstacleMask routeMask = baseMask.withoutEndpoints(from, destination);
            TerrainSamplingCache routeCache = new TerrainSamplingCache(level, config.getSamplingPrecision(), routeMask.blockedColumns());
            PathResult result = pathfinder.findPath(from, destination, routeCache);
            if (!result.success() || routeMask.pathTouchesBlockedColumn(result.path())) {
                return List.of();
            }
            return result.path();
        };
        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(terrainCache, new RoadConfig().getBridge())
        );
    }
}
