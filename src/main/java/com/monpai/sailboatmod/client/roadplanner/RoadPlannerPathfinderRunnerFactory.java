package com.monpai.sailboatmod.client.roadplanner;

import com.mojang.logging.LogUtils;
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
import org.slf4j.Logger;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

public final class RoadPlannerPathfinderRunnerFactory {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadPlannerPathfinderRunnerFactory() {
    }

    record RouteRun(List<BlockPos> path, Diagnostics diagnostics) {
        RouteRun {
            path = immutablePath(path);
        }
    }

    record Diagnostics(PathfindingConfig.Algorithm coarseAlgorithm,
                       BlockPos originalStart,
                       BlockPos originalDestination,
                       BlockPos routeStart,
                       BlockPos routeDestination,
                       boolean coarseSuccess,
                       int coarseNodeCount,
                       String coarseFailureReason,
                       boolean coarseRejectedByMask,
                       boolean fineSuccess,
                       int fineNodeCount,
                       String fineFailureReason,
                       boolean fineRejectedByMask,
                       int finalNodeCount,
                       double maxLateralDeviation) {
    }

    public static RoadPlannerAutoCompleteService.PathfinderRunner serverRunner(ServerLevel level) {
        RoadPlannerAutoCompleteService service = serverService(level, PathfindingConfig.Algorithm.BIDIRECTIONAL_ASTAR);
        return service == null ? null : service::runPathfinderOnly;
    }

    public static RoadPlannerAutoCompleteService serverService(ServerLevel level, PathfindingConfig.Algorithm algorithm) {
        if (level == null) {
            return null;
        }
        PathfindingConfig coarseConfig = new PathfindingConfig();
        coarseConfig.setAlgorithm(algorithm == null ? PathfindingConfig.Algorithm.SEGMENTED_ADAPTIVE : algorithm);
        Pathfinder coarsePathfinder = PathfinderFactory.create(coarseConfig);

        PathfindingConfig fineConfig = fineStageConfig();
        Pathfinder finePathfinder = PathfinderFactory.create(fineConfig);

        RoadPlannerObstacleMask baseMask = RoadPlannerObstacleMask.fromNationData(level, NationSavedData.get(level));
        TerrainSamplingCache terrainCache = new TerrainSamplingCache(level, coarseConfig.getSamplingPrecision());

        RoadPlannerAutoCompleteService.PathfinderRunner runner = (BlockPos from, BlockPos destination) -> {
            TerrainSamplingCache coarseCache = new TerrainSamplingCache(level, coarseConfig.getSamplingPrecision(), baseMask.blockedColumns());
            RouteRun run = runTwoStagePath(
                    from,
                    destination,
                    coarseConfig.getAlgorithm(),
                    baseMask,
                    coarseCache,
                    coarsePathfinder,
                    finePathfinder,
                    (coarsePath, routeMask) -> buildCorridorCache(level, coarsePath, routeMask, fineConfig.getSamplingPrecision(), 32)
            );
            logDiagnostics(run.diagnostics());
            return run.path();
        };

        return new RoadPlannerAutoCompleteService(
                runner,
                new RoadPlannerTerrainSegmentClassifier(terrainCache, new RoadConfig().getBridge()),
                (x, z) -> !terrainCache.isWater(x, z)
        );
    }

    static RouteRun runTwoStagePathForTest(BlockPos from,
                                           BlockPos destination,
                                           PathfindingConfig.Algorithm algorithm,
                                           RoadPlannerObstacleMask baseMask,
                                           TerrainSamplingCache terrainCache,
                                           Pathfinder coarsePathfinder,
                                           Pathfinder finePathfinder) {
        return runTwoStagePath(
                from,
                destination,
                algorithm,
                baseMask,
                terrainCache,
                coarsePathfinder,
                finePathfinder,
                (coarsePath, routeMask) -> terrainCache
        );
    }

    private static RouteRun runTwoStagePath(BlockPos from,
                                            BlockPos destination,
                                            PathfindingConfig.Algorithm algorithm,
                                            RoadPlannerObstacleMask baseMask,
                                            TerrainSamplingCache terrainCache,
                                            Pathfinder coarsePathfinder,
                                            Pathfinder finePathfinder,
                                            BiFunction<List<BlockPos>, RoadPlannerObstacleMask, TerrainSamplingCache> fineCacheFactory) {
        RoadPlannerObstacleMask mask = baseMask == null ? RoadPlannerObstacleMask.empty() : baseMask;
        BlockPos originalStart = from == null ? BlockPos.ZERO : from.immutable();
        BlockPos originalDestination = destination == null ? BlockPos.ZERO : destination.immutable();
        BlockPos routeStart = adjustEndpointForObstacles(originalStart, originalDestination, mask, terrainCache);
        BlockPos routeDestination = adjustEndpointForObstacles(originalDestination, originalStart, mask, terrainCache);
        RoadPlannerObstacleMask routeMask = mask.withoutEndpoints(routeStart, routeDestination);

        PathResult coarseResult = coarsePathfinder == null
                ? PathResult.failure("coarse pathfinder missing")
                : coarsePathfinder.findPath(routeStart, routeDestination, terrainCache);
        List<BlockPos> coarsePath = resultPath(coarseResult);
        boolean coarseSuccess = coarseResult != null && coarseResult.success();
        boolean coarseRejected = coarseSuccess && routeMask.pathTouchesBlockedColumn(coarsePath);
        if (!coarseSuccess || coarseRejected) {
            List<BlockPos> finalPath = List.of();
            return new RouteRun(finalPath, new Diagnostics(
                    algorithm,
                    originalStart,
                    originalDestination,
                    routeStart,
                    routeDestination,
                    coarseSuccess,
                    coarsePath.size(),
                    coarseResult == null ? "coarse pathfinder returned null" : coarseResult.failureReason(),
                    coarseRejected,
                    false,
                    0,
                    "fine skipped",
                    false,
                    finalPath.size(),
                    maxLateralDeviation(finalPath, originalStart, originalDestination)
            ));
        }

        TerrainSamplingCache fineCache = fineCacheFactory == null ? terrainCache : fineCacheFactory.apply(coarsePath, routeMask);
        if (fineCache == null) {
            fineCache = terrainCache;
        }
        PathResult fineResult = finePathfinder == null
                ? PathResult.failure("fine pathfinder missing")
                : finePathfinder.findPath(routeStart, routeDestination, fineCache);
        List<BlockPos> finePath = resultPath(fineResult);
        boolean fineSuccess = fineResult != null && fineResult.success();
        boolean fineRejected = fineSuccess && routeMask.pathTouchesBlockedColumn(finePath);
        List<BlockPos> finalPath = fineSuccess && !fineRejected ? finePath : coarsePath;

        return new RouteRun(finalPath, new Diagnostics(
                algorithm,
                originalStart,
                originalDestination,
                routeStart,
                routeDestination,
                true,
                coarsePath.size(),
                coarseResult.failureReason(),
                false,
                fineSuccess,
                finePath.size(),
                fineResult == null ? "fine pathfinder returned null" : fineResult.failureReason(),
                fineRejected,
                finalPath.size(),
                maxLateralDeviation(finalPath, originalStart, originalDestination)
        ));
    }

    private static PathfindingConfig fineStageConfig() {
        PathfindingConfig fineConfig = new PathfindingConfig();
        fineConfig.setAlgorithm(PathfindingConfig.Algorithm.POTENTIAL_FIELD);
        fineConfig.setAStarStep(4);
        fineConfig.setSamplingPrecision(PathfindingConfig.SamplingPrecision.HIGH);
        return fineConfig;
    }

    static PathfindingConfig fineStageConfigForTest() {
        return fineStageConfig();
    }

    private static void logDiagnostics(Diagnostics diagnostics) {
        if (diagnostics == null) {
            return;
        }
        LOGGER.info(
                "[RoadPlannerPathfinder] algorithm={} original={} -> {} route={} -> {} coarseSuccess={} coarseNodes={} coarseRejected={} coarseReason={} fineSuccess={} fineNodes={} fineRejected={} fineReason={} finalNodes={} maxDeviation={}",
                diagnostics.coarseAlgorithm(),
                diagnostics.originalStart(),
                diagnostics.originalDestination(),
                diagnostics.routeStart(),
                diagnostics.routeDestination(),
                diagnostics.coarseSuccess(),
                diagnostics.coarseNodeCount(),
                diagnostics.coarseRejectedByMask(),
                diagnostics.coarseFailureReason(),
                diagnostics.fineSuccess(),
                diagnostics.fineNodeCount(),
                diagnostics.fineRejectedByMask(),
                diagnostics.fineFailureReason(),
                diagnostics.finalNodeCount(),
                diagnostics.maxLateralDeviation()
        );
    }

    private static TerrainSamplingCache buildCorridorCache(ServerLevel level,
                                                           List<BlockPos> corridorPath,
                                                           RoadPlannerObstacleMask mask,
                                                           PathfindingConfig.SamplingPrecision precision,
                                                           int radius) {
        RoadPlannerObstacleMask safeMask = mask == null ? RoadPlannerObstacleMask.empty() : mask;
        TerrainSamplingCache cache = new TerrainSamplingCache(
                level,
                precision,
                safeMask.blockedColumns(),
                corridorColumns(corridorPath, radius, 4)
        );
        if (corridorPath == null) {
            return cache;
        }
        for (BlockPos pos : corridorPath) {
            if (pos == null) {
                continue;
            }
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

    private static List<BlockPos> resultPath(PathResult result) {
        return result == null ? List.of() : immutablePath(result.path());
    }

    private static List<BlockPos> immutablePath(List<BlockPos> path) {
        if (path == null || path.isEmpty()) {
            return List.of();
        }
        return path.stream()
                .filter(pos -> pos != null)
                .map(BlockPos::immutable)
                .toList();
    }

    static double maxLateralDeviation(List<BlockPos> path, BlockPos start, BlockPos end) {
        if (path == null || path.isEmpty() || start == null || end == null) {
            return 0.0D;
        }
        double ax = start.getX();
        double az = start.getZ();
        double bx = end.getX();
        double bz = end.getZ();
        double dx = bx - ax;
        double dz = bz - az;
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < 1.0e-6D) {
            return 0.0D;
        }
        double max = 0.0D;
        for (BlockPos pos : path) {
            if (pos == null) {
                continue;
            }
            double px = pos.getX();
            double pz = pos.getZ();
            double distance = Math.abs(dz * px - dx * pz + bx * az - bz * ax) / len;
            max = Math.max(max, distance);
        }
        return max;
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
