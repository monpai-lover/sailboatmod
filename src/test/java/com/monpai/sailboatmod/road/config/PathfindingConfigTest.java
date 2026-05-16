package com.monpai.sailboatmod.road.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathfindingConfigTest {
    @Test
    void defaultAlgorithmUsesSegmentedAdaptive() {
        assertEquals(PathfindingConfig.Algorithm.SEGMENTED_ADAPTIVE, new PathfindingConfig().getAlgorithm());
    }
}
