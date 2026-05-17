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

import java.util.List;

public final class RoadPlannerPathfinderRunnerFactory {
    private RoadPlannerPathfinderRunnerFactory() {
    }

    public static RoadPlannerAutoCompleteService.PathfinderRunner serverRunner(ServerLevel level) {
        RoadPlannerAutoCompleteService service = serverService(level, PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
        return service == null ? null : service::runPathfinderOnly;
    }

    public static RoadPlannerAutoCompleteService serverService(ServerLevel level, PathfindingConfig.Algorithm algorithm) {
        if (level == null) {
            return null;
        }
        PathfindingConfig config = new PathfindingConfig();
        config.setAlgorithm(algorithm);
        Pathfinder pathfinder = PathfinderFactory.create(config);

        RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
        TerrainSamplingCache terrainCache = new TerrainSamplingCache(level, config.getSamplingPrecision());

        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            BlockPos routeStart = adjustEndpointForObstacles(from, destination, baseMask, terrainCache);
            BlockPos routeDestination = adjustEndpointForObstacles(destination, from, baseMask, terrainCache);
            RoadPlannerObstacleMask routeMask = baseMask.withoutEndpoints(routeStart, routeDestination);
            TerrainSamplingCache routeCache = new TerrainSamplingCache(level, config.getSamplingPrecision(), routeMask.blockedColumns());
            PathResult result = pathfinder.findPath(routeStart, routeDestination, routeCache);
            if (!result.success() || routeMask.pathTouchesBlockedColumn(result.path())) {
                return List.of();
            }
            return result.path();
        };

        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(terrainCache, new RoadConfig().getBridge()),
                (x, z) -> !terrainCache.isWater(x, z)
        );
    }

    static BlockPos adjustEndpointForObstacles(BlockPos endpoint,
                                               BlockPos opposite,
                                               RoadPlannerObstacleMask mask,
                                               TerrainSamplingCache cache) {
        if (endpoint == null) {
            return BlockPos.ZERO;
        }
        if (mask == null || !mask.isBlocked(endpoint)) {
            return endpoint.immutable();
        }

        double desiredX = opposite == null ? 0.0D : opposite.getX() - endpoint.getX();
        double desiredZ = opposite == null ? 0.0D : opposite.getZ() - endpoint.getZ();
        double desiredLen = Math.sqrt(desiredX * desiredX + desiredZ * desiredZ);
        int maxRadius = RoadCoreExclusion.DEFAULT_RADIUS + 12;

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int radius = 1; radius <= maxRadius; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    int x = endpoint.getX() + dx;
                    int z = endpoint.getZ() + dz;
                    if (mask.isBlocked(x, z)) {
                        continue;
                    }
                    double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                    double score = dist;
                    if (desiredLen > 1e-6D && dist > 1e-6D) {
                        double alignment = (dx * desiredX + dz * desiredZ) / (dist * desiredLen);
                        score += (1.0D - alignment) * 4.0D;
                    }
                    if (score < bestScore) {
                        bestScore = score;
                        int y = cache == null ? endpoint.getY() : cache.getHeight(x, z);
                        best = new BlockPos(x, y, z);
                    }
                }
            }
            if (best != null) {
                return best.immutable();
            }
        }
        return endpoint.immutable();
    }
}
