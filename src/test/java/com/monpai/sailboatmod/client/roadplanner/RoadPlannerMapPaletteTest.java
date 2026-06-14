package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerMapPaletteTest {
    @Test
    void softenTerrainKeepsSampledMapColorUnchanged() {
        int source = 0xFF123456;
        int softened = RoadPlannerMapPalette.softenTerrain(source);

        assertEquals(source, softened);
    }

    @Test
    void softenWaterKeepsSampledMapColorUnchanged() {
        int source = 0xFF001155;
        int softened = RoadPlannerMapPalette.softenWater(source);

        assertEquals(source, softened);
    }
}
