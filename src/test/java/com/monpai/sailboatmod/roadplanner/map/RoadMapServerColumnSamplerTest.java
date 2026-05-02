package com.monpai.sailboatmod.roadplanner.map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadMapServerColumnSamplerTest {
    @Test
    void unavailableSampleUsesStableUnknownColor() {
        RoadMapColumnSample sample = RoadMapServerColumnSampler.unavailableSampleForTest(10, -20);

        assertEquals(10, sample.worldX());
        assertEquals(-20, sample.worldZ());
        assertEquals(0xFF2A2A2A, sample.baseArgb());
        assertFalse(sample.water());
        assertEquals(0, sample.waterDepth());
    }
}
