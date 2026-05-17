package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMask;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerPathfinderRunnerFactoryTest {
    @Test
    void movesBlockedTownCoreEndpointToNearbyUnblockedRoadAnchor() {
        BlockPos source = new BlockPos(0, 64, 0);
        BlockPos destinationCore = new BlockPos(64, 64, 0);
        RoadPlannerObstacleMask mask = RoadPlannerObstacleMask.fromColumns(
                RoadPlannerObstacleMask.coreColumns(List.of(destinationCore), List.of())
        );

        BlockPos adjusted = RoadPlannerPathfinderRunnerFactory.adjustEndpointForObstacles(
                destinationCore,
                source,
                mask,
                flatTerrain()
        );

        assertFalse(mask.isBlocked(adjusted), "adjusted endpoint must not stay inside the core exclusion mask");
        assertTrue(adjusted.getX() < destinationCore.getX(), "destination road anchor should face the source side");
        assertTrue(Math.abs(adjusted.getX() - destinationCore.getX()) > RoadCoreExclusion.DEFAULT_RADIUS
                        || Math.abs(adjusted.getZ() - destinationCore.getZ()) > RoadCoreExclusion.DEFAULT_RADIUS,
                "destination road anchor should be outside the protected core radius");
    }

    @Test
    void twoStagePathfinderReturnsFinePathWhenFineStageSucceeds() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(64, 64, 0);
        List<BlockPos> coarse = List.of(start, new BlockPos(32, 64, 8), end);
        List<BlockPos> fine = List.of(start, new BlockPos(24, 64, 12), new BlockPos(48, 64, 12), end);

        RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
                start,
                end,
                PathfindingConfig.Algorithm.POTENTIAL_FIELD,
                RoadPlannerObstacleMask.empty(),
                flatTerrain(),
                recordingPathfinder(coarse),
                recordingPathfinder(fine)
        );

        assertEquals(fine, run.path());
        assertTrue(run.diagnostics().fineSuccess());
        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, run.diagnostics().coarseAlgorithm());
    }

    @Test
    void twoStagePathfinderFallsBackToCoarsePathWhenFineStageFails() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(64, 64, 0);
        List<BlockPos> coarse = List.of(start, new BlockPos(32, 64, 8), end);

        RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
                start,
                end,
                PathfindingConfig.Algorithm.GRADIENT_DESCENT,
                RoadPlannerObstacleMask.empty(),
                flatTerrain(),
                recordingPathfinder(coarse),
                failingPathfinder("fine failed")
        );

        assertEquals(coarse, run.path());
        assertEquals(PathfindingConfig.Algorithm.GRADIENT_DESCENT, run.diagnostics().requestedAlgorithm());
        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, run.diagnostics().coarseAlgorithm());
        assertTrue(run.diagnostics().coarseSuccess());
        assertFalse(run.diagnostics().fineSuccess());
    }

    @Test
    void twoStagePathfinderReturnsEmptyPathWhenCoarseStageFails() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(64, 64, 0);

        RoadPlannerPathfinderRunnerFactory.RouteRun run = RoadPlannerPathfinderRunnerFactory.runTwoStagePathForTest(
                start,
                end,
                PathfindingConfig.Algorithm.BASIC_ASTAR,
                RoadPlannerObstacleMask.empty(),
                flatTerrain(),
                failingPathfinder("coarse failed"),
                recordingPathfinder(List.of(start, end))
        );

        assertTrue(run.path().isEmpty());
        assertFalse(run.diagnostics().coarseSuccess());
        assertFalse(run.diagnostics().fineSuccess());
    }

    @Test
    void fineStageConfigUsesHighPrecisionPotentialFieldWithFourBlockStep() {
        PathfindingConfig config = RoadPlannerPathfinderRunnerFactory.fineStageConfigForTest();

        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, config.getAlgorithm());
        assertEquals(4, config.getAStarStep());
        assertEquals(PathfindingConfig.SamplingPrecision.HIGH, config.getSamplingPrecision());
    }

    @Test
    void coarseStageConfigIgnoresRequestedAlgorithmAndUsesPotentialField() {
        PathfindingConfig config = RoadPlannerPathfinderRunnerFactory.coarseStageConfigForTest(
                PathfindingConfig.Algorithm.BASIC_ASTAR
        );

        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, config.getAlgorithm());
        assertEquals(8, config.getAStarStep());
        assertEquals(PathfindingConfig.SamplingPrecision.NORMAL, config.getSamplingPrecision());
    }

    private static Pathfinder recordingPathfinder(List<BlockPos> path) {
        return (start, end, cache) -> PathResult.success(path);
    }

    private static Pathfinder failingPathfinder(String reason) {
        return (start, end, cache) -> PathResult.failure(reason);
    }

    private static TerrainSamplingCache flatTerrain() {
        return new TerrainSamplingCache(null, PathfindingConfig.SamplingPrecision.NORMAL) {
            @Override
            public int getHeight(int x, int z) {
                return 64;
            }
        };
    }
}
