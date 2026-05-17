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
        config.setAlgorithm(PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
        Pathfinder pathfinder = PathfinderFactory.create(config);
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getSamplingPrecision());
        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            PathResult result = pathfinder.findPath(from, destination, cache);
            return result.success() ? result.path() : List.of();
        };
        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(cache, new RoadConfig().getBridge())
        );
    }
}
