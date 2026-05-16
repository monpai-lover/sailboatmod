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
        PathfindingConfig coarseConfig = new PathfindingConfig();
        coarseConfig.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        Pathfinder coarsePathfinder = PathfinderFactory.create(coarseConfig);

        PathfindingConfig fineConfig = new PathfindingConfig();
        fineConfig.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        fineConfig.setAStarStep(4);
        fineConfig.setSamplingPrecision(PathfindingConfig.SamplingPrecision.HIGH);
        Pathfinder finePathfinder = PathfinderFactory.create(fineConfig);

        RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
        TerrainSamplingCache terrainCache = new TerrainSamplingCache(level, coarseConfig.getSamplingPrecision());

        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            RoadPlannerObstacleMask routeMask = baseMask.withoutEndpoints(from, destination);

            // Stage 1: coarse pathfinding (step=8, NORMAL precision)
            TerrainSamplingCache coarseCache = new TerrainSamplingCache(level, coarseConfig.getSamplingPrecision(), routeMask.blockedColumns());
            PathResult coarseResult = coarsePathfinder.findPath(from, destination, coarseCache);
            if (!coarseResult.success() || routeMask.pathTouchesBlockedColumn(coarseResult.path())) {
                return List.of();
            }

            // Stage 2: fine pathfinding along corridor (step=4, HIGH precision)
            TerrainSamplingCache fineCache = buildCorridorCache(level, coarseResult.path(), routeMask, fineConfig.getSamplingPrecision(), 32);
            PathResult fineResult = finePathfinder.findPath(from, destination, fineCache);
            if (fineResult.success() && !routeMask.pathTouchesBlockedColumn(fineResult.path())) {
                return fineResult.path();
            }
            return coarseResult.path();
        };

        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(terrainCache, new RoadConfig().getBridge())
        );
    }

    private static TerrainSamplingCache buildCorridorCache(ServerLevel level, List<BlockPos> corridorPath,
                                                            RoadPlannerObstacleMask mask,
                                                            PathfindingConfig.SamplingPrecision precision,
                                                            int radius) {
        TerrainSamplingCache cache = new TerrainSamplingCache(level, precision, mask.blockedColumns());
        for (BlockPos pos : corridorPath) {
            for (int dx = -radius; dx <= radius; dx += 8) {
                for (int dz = -radius; dz <= radius; dz += 8) {
                    cache.getHeight(pos.getX() + dx, pos.getZ() + dz);
                }
            }
        }
        return cache;
    }
}
