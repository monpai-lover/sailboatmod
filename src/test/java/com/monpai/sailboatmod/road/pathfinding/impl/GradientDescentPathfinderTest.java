package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class GradientDescentPathfinderTest {
    @Test
    void squaredElevationCostAvoidsShortSteepRidge() {
        PathfindingConfig config = new PathfindingConfig();
        config.setAStarStep(4);
        config.setMaxSteps(4000);
        config.setDeviationWeight(0.05);

        TestTerrainSamplingCache cache = new TestTerrainSamplingCache((x, z) ->
                x >= 8 && x <= 56 && z == 0 ? 110 : 64
        );

        PathResult result = new GradientDescentPathfinder(config).findPath(
                new BlockPos(0, 64, 0),
                new BlockPos(64, 64, 0),
                cache
        );

        assertTrue(result.success(), result.failureReason());
        assertTrue(result.path().stream().anyMatch(pos ->
                pos.getX() >= 8 && pos.getX() <= 56 && pos.getZ() != 0
        ), "path should detour around the steep ridge instead of climbing it directly");
    }
}
