package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerPathfinderRunnerFactoryTest {
    @Test
    void autocompletePathfinderUsesPotentialField() {
        PathfindingConfig config = RoadPlannerPathfinderRunnerFactory.serverPathfindingConfigForTest();

        assertEquals(PathfindingConfig.Algorithm.POTENTIAL_FIELD, config.getAlgorithm());
        assertEquals(8, config.getAStarStep());
        assertEquals(PathfindingConfig.SamplingPrecision.NORMAL, config.getSamplingPrecision());
    }
}
