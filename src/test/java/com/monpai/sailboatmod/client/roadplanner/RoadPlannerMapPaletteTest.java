package com.monpai.sailboatmod.client.roadplanner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapPaletteTest {
    @Test
    void softenTerrainPreservesAlphaAndIncreasesBrightness() {
        int source = 0xFF123456;
        int softened = RoadPlannerMapPalette.softenTerrain(source);

        assertEquals(0xFF, (softened >>> 24) & 0xFF);
        assertTrue(brightness(softened) > brightness(source));
    }

    @Test
    void softenWaterStaysBlueDominantWhileLighter() {
        int source = 0xFF001155;
        int softened = RoadPlannerMapPalette.softenWater(source);

        int red = (softened >>> 16) & 0xFF;
        int green = (softened >>> 8) & 0xFF;
        int blue = softened & 0xFF;
        assertTrue(blue > red);
        assertTrue(blue > green);
        assertTrue(brightness(softened) > brightness(source));
    }

    private static int brightness(int argb) {
        return ((argb >>> 16) & 0xFF) + ((argb >>> 8) & 0xFF) + (argb & 0xFF);
    }
}
