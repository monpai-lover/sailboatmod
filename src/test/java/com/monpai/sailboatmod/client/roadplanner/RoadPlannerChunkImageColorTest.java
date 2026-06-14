package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapBlockColors;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import net.minecraft.world.level.material.MapColor;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class RoadPlannerChunkImageColorTest {
    @Test
    void terrainUsesVanillaMapColorInsteadOfSharedBlockColorOverrides() {
        int actual = RoadPlannerChunkImage.terrainArgbForMapColor(MapColor.GRASS, 0);
        int oldClientColor = RoadPlannerMapPalette.softenTerrain(
                0xFF000000 | MapColor.GRASS.calculateRGBColor(MapColor.Brightness.LOW));
        int sharedBlockColor = RoadMapRenderStyle.styleTerrainByDelta(
                MapBlockColors.lookup("minecraft:grass_block"), 0);

        assertEquals(oldClientColor, actual);
        assertNotEquals(sharedBlockColor, actual);
    }

    @Test
    void waterUsesVanillaMapColorInsteadOfFixedWaterOverride() {
        int actual = RoadPlannerChunkImage.waterArgbForDepth(1);
        int oldClientColor = RoadPlannerMapPalette.softenWater(
                0xFF000000 | MapColor.WATER.calculateRGBColor(MapColor.Brightness.NORMAL));
        int sharedFixedWaterColor = RoadMapRenderStyle.styleWater(1);

        assertEquals(oldClientColor, actual);
        assertNotEquals(sharedFixedWaterColor, actual);
    }

    @Test
    void clientPaletteDoesNotBleachTerrainOrWater() {
        int terrain = 0xFF4F7E2C;
        int water = 0xFF24486A;

        assertEquals(terrain, RoadPlannerMapPalette.softenTerrain(terrain));
        assertEquals(water, RoadPlannerMapPalette.softenWater(water));
    }

    @Test
    void webMapStyleKeepsServerBlockColorsReadableWithoutBleaching() {
        int grass = MapBlockColors.lookup("minecraft:grass_block");
        int water = MapBlockColors.waterArgb();

        assertEquals(grass, RoadMapRenderStyle.softenTerrain(grass));
        assertEquals(water, RoadMapRenderStyle.softenWater(water));
    }
}
