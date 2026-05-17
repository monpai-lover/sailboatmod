package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import com.monpai.sailboatmod.roadplanner.obstacle.RoadPlannerObstacleMask;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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

    private static TerrainSamplingCache flatTerrain() {
        return new TerrainSamplingCache(null, PathfindingConfig.SamplingPrecision.NORMAL) {
            @Override
            public int getHeight(int x, int z) {
                return 64;
            }
        };
    }
}
