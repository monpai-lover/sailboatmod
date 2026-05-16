package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SegmentedAdaptivePathfinderTest {
    @Test
    void shortPathClassifierSelectsAlgorithmFromTerrain() throws Exception {
        SegmentedAdaptivePathfinder pathfinder = new SegmentedAdaptivePathfinder(new PathfindingConfig());
        Method classifySingle = SegmentedAdaptivePathfinder.class.getDeclaredMethod(
                "classifySingle",
                BlockPos.class,
                BlockPos.class,
                com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache.class
        );
        classifySingle.setAccessible(true);

        TestTerrainSamplingCache flat = new TestTerrainSamplingCache((x, z) -> 64);
        TestTerrainSamplingCache water = new TestTerrainSamplingCache((x, z) -> 64, (x, z) -> x == 0 && z == 0, Set.of());

        assertEquals(PathfindingConfig.Algorithm.BASIC_ASTAR,
                classifySingle.invoke(pathfinder, new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), flat));
        assertEquals(PathfindingConfig.Algorithm.GRADIENT_DESCENT,
                classifySingle.invoke(pathfinder, new BlockPos(0, 64, 0), new BlockPos(32, 74, 0), flat));
        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD,
                classifySingle.invoke(pathfinder, new BlockPos(0, 64, 0), new BlockPos(32, 64, 0), water));
    }
}
