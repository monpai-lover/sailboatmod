package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPathfinderTest {
    @Test
    void routeColumnTreatsWaterAsBlockedDuringLandOnlyPass() {
        assertTrue(RoadPathfinder.isBridgeBlockedForModeForTest(true, false));
    }

    @Test
    void routeColumnAllowsWaterDuringFallbackPass() {
        assertFalse(RoadPathfinder.isBridgeBlockedForModeForTest(true, true));
    }
}
