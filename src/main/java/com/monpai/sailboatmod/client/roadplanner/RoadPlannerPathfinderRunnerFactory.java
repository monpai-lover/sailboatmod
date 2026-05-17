package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        PathfindingConfig fineConfig = new PathfindingConfig();
        fineConfig.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        fineConfig.setAStarStep(4);
        fineConfig.setSamplingPrecision(PathfindingConfig.SamplingPrecision.HIGH);
        Pathfinder finePathfinder = PathfinderFactory.create(fineConfig);

        RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
        TerrainSamplingCache terrainCache = new TerrainSamplingCache(level, PathfindingConfig.SamplingPrecision.NORMAL);

        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            RoadPlannerObstacleMask routeMask = baseMask.withoutEndpoints(from, destination);

            // Stage 1: coarse pathfinding (step=8, BIDIRECTIONAL_ASTAR)
            PathfindingConfig coarseConfig = new PathfindingConfig();
            coarseConfig.setAlgorithm(PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
            Pathfinder coarsePathfinder = PathfinderFactory.create(coarseConfig);
            TerrainSamplingCache coarseCache = new TerrainSamplingCache(level, coarseConfig.getSamplingPrecision(), routeMask.blockedColumns());
            PathResult coarseResult = coarsePathfinder.findPath(from, destination, coarseCache);
            if (!coarseResult.success() || routeMask.pathTouchesBlockedColumn(coarseResult.path())) {
                return List.of();
            }

            // Stage 2: fine pathfinding along corridor (step=4, HIGH precision, PotentialField)
            TerrainSamplingCache fineCache = buildCorridorCache(level, coarseResult.path(), routeMask, fineConfig.getSamplingPrecision(), 32);
            PathResult fineResult = finePathfinder.findPath(from, destination, fineCache);
            if (fineResult.success() && !routeMask.pathTouchesBlockedColumn(fineResult.path())) {
                return fineResult.path();
            }
            return coarseResult.path();
        };

        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(terrainCache, new RoadConfig().getBridge()),
                (x, z) -> !terrainCache.isWater(x, z)
        );
    }

    private static TerrainSamplingCache buildCorridorCache(ServerLevel level, List<BlockPos> corridorPath,
                                                            RoadPlannerObstacleMask mask,
                                                            PathfindingConfig.SamplingPrecision precision,
                                                            int radius) {
        TerrainSamplingCache cache = new TerrainSamplingCache(
                level,
                precision,
                mask.blockedColumns(),
                corridorColumns(corridorPath, radius, 4)
        );
        for (BlockPos pos : corridorPath) {
            for (int dx = -radius; dx <= radius; dx += 4) {
                for (int dz = -radius; dz <= radius; dz += 4) {
                    cache.getHeight(pos.getX() + dx, pos.getZ() + dz);
                }
            }
        }
        return cache;
    }

    static Set<Long> corridorColumns(List<BlockPos> corridorPath, int radius, int step) {
        if (corridorPath == null || corridorPath.isEmpty()) {
            return Set.of();
        }
        int safeRadius = Math.max(0, radius);
        int safeStep = Math.max(1, step);
        Set<Long> columns = new HashSet<>();
        for (BlockPos pos : corridorPath) {
            if (pos == null) {
                continue;
            }
            for (int dx = -safeRadius; dx <= safeRadius; dx += safeStep) {
                for (int dz = -safeRadius; dz <= safeRadius; dz += safeStep) {
                    columns.add(RoadCoreExclusion.columnKey(pos.getX() + dx, pos.getZ() + dz));
                }
            }
        }
        return Set.copyOf(columns);
    }
}
