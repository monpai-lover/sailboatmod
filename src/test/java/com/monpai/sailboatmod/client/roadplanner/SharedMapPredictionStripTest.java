package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class SharedMapPredictionStripTest {
    @Test void stripsMergeInOneUnderlayAndTransparentDataDoesNotEraseKnownPixels() throws Exception {
        RoadPlannerTile tile = new RoadPlannerTile(new RoadPlannerTileKey("earth", "minecraft:overworld", MapLod.LOD_1, 0, 0));
        var field = RoadPlannerTile.class.getDeclaredField("predictionPixels");
        field.setAccessible(true);
        int[] strip = new int[4096];
        Arrays.fill(strip, 0xFF123456);
        assertTrue(tile.applyPredictionPatch(0, 0, 256, 16, strip));
        int[] first = (int[]) field.get(tile);
        assertTrue(tile.applyPredictionPatch(0, 16, 256, 16, strip));
        assertSame(first, field.get(tile));
        assertEquals(0xFF123456, first[4096]);
        assertTrue(tile.applyPredictionPatch(0, 0, 256, 16, new int[4096]));
        assertEquals(0xFF123456, first[0]);
        assertFalse(tile.applyPredictionPatch(Integer.MAX_VALUE, 0, 256, 16, strip));
    }
}
