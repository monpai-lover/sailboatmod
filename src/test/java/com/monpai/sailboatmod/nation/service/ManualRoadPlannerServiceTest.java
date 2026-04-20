package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerServiceTest {
    @Test
    void detourRejectsLongCrossingThatExceedsShortSpanThreshold() {
        assertTrue(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(8));
        assertFalse(ManualRoadPlannerService.allowsPierlessDetourCrossingForTest(9));
    }
}
