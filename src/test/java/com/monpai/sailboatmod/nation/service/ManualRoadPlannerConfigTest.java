package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerConfigTest {
    @Test
    void plannerConfigNormalizesUnsupportedWidths() {
        ManualRoadPlannerConfig config = ManualRoadPlannerConfig.normalized(9, "sandstone", true);

        assertEquals(7, config.width());
        assertEquals("sandstone", config.materialPreset());
        assertTrue(config.tunnelEnabled());
    }
}
