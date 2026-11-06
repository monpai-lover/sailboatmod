package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerPredictionStripTest {
    @Test void stripsMergeIntoOneOwnedPredictionArrayWithoutTouchingActualImage() throws Exception {
        RoadPlannerTile tile = new RoadPlannerTile(new RoadPlannerTileKey("earth", "minecraft:overworld", MapLod.LOD_1, 0, 0));
        Field pixels = RoadPlannerTile.class.getDeclaredField("predictionPixels"); pixels.setAccessible(true);
        Field actual = RoadPlannerTile.class.getDeclaredField("image"); actual.setAccessible(true);
        assertNull(pixels.get(tile));
        int[] strip = new int[4096]; Arrays.fill(strip, 0xFF123456);
        assertTrue(tile.applyPredictionPatch(0, 0, 256, 16, strip));
        int[] owned = (int[]) pixels.get(tile);
        assertNotSame(strip, owned);
        assertEquals(65536, owned.length);
        assertEquals(0, owned[4096]);
        Arrays.fill(strip, 0xFF654321);
        assertTrue(tile.applyPredictionPatch(0, 240, 256, 16, strip));
        assertSame(owned, pixels.get(tile), "later strips must not clone the full tile");
        assertEquals(0xFF123456, owned[0]);
        assertEquals(0xFF654321, owned[65535]);
        assertNull(actual.get(tile), "prediction must not become actual terrain");
    }
    @Test void invalidStripsDoNotAllocateFullTileStorage() throws Exception {
        RoadPlannerTile tile = new RoadPlannerTile(new RoadPlannerTileKey("earth", "minecraft:overworld", MapLod.LOD_1, 0, 0));
        assertFalse(tile.applyPredictionPatch(255, 0, 256, 16, new int[4096]));
        assertFalse(tile.applyPredictionPatch(0, 0, 256, 256, new int[65536]));
        Field pixels = RoadPlannerTile.class.getDeclaredField("predictionPixels"); pixels.setAccessible(true);
        assertNull(pixels.get(tile));
    }
}
