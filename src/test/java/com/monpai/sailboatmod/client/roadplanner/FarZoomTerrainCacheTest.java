package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class FarZoomTerrainCacheTest {
    @TempDir Path cache;
    @Test void fineAndCoarsePredictionAndActualPixelsUseIndependentCacheNamespaces() throws Exception {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(cache.toFile(), "earth", "minecraft:overworld")) {
            int[] coarse = filled(256*256, 0xFF112233), fine = filled(256*256, 0xFF445566);
            apply(manager, 0, 0, 0, 0, 256, 256, coarse, true, 64);
            apply(manager, 0, 0, 0, 0, 256, 256, fine, true, 1);
            RoadPlannerTile coarseTile = tile(manager, 0, 0, 64), fineTile = tile(manager, 0, 0, 1);
            assertNotSame(coarseTile, fineTile);
            assertEquals(0xFF112233, coarseTile.copyDisplayPixels()[0]);
            assertEquals(0xFF445566, fineTile.copyDisplayPixels()[0]);
            apply(manager, 0, 0, 0, 0, 1, 1, new int[]{0xFFABCDEF}, false, 64);
            assertEquals(0xFFABCDEF, coarseTile.copyDisplayPixels()[0]);
            assertEquals(0xFF445566, fineTile.copyDisplayPixels()[0]);
            assertTrue(coarseTile.copyPixels().length > 0);
        }
    }
    @Test void oneActualCoarseCentreNeverMarksTheWholeUnderlyingChunkAsRendered() throws Exception {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(cache.toFile(), "earth", "minecraft:overworld")) {
            SharedMapClientState state = new SharedMapClientState(manager);
            var method = SharedMapClientState.class.getMethod("applyTerrainPatch", String.class, String.class,
                    int.class, int.class, int.class, int.class, int.class, int.class, int[].class, boolean.class, int.class);
            assertEquals(1, method.invoke(state, "earth", "minecraft:overworld", 0, 0, 40, 80, 1, 1, new int[]{0xFF123456}, false, 64));
            assertFalse(state.isRendered("minecraft:overworld", 162, 322));
            assertEquals(1, method.invoke(state, "earth", "minecraft:overworld", 10, 20, 32, 32, 16, 16, filled(256, 0xFFABCDEF), false, 1));
            assertTrue(state.isRendered("minecraft:overworld", 162, 322));
        }
    }
    private static RoadPlannerTile tile(RoadPlannerTileManager manager, int x, int z, int bps) throws Exception {
        return (RoadPlannerTile) RoadPlannerTileManager.class.getMethod("getTerrainTile", int.class, int.class, int.class).invoke(manager, x, z, bps);
    }
    private static void apply(RoadPlannerTileManager manager, int tileX, int tileZ, int x, int z, int w, int h, int[] pixels, boolean prediction, int bps) throws Exception {
        RoadPlannerTileManager.class.getMethod("applyTerrainPatch", String.class, String.class, int.class, int.class,
                int.class, int.class, int.class, int.class, int[].class, boolean.class, int.class)
                .invoke(manager, "earth", "minecraft:overworld", tileX, tileZ, x, z, w, h, pixels, prediction, bps);
    }
    private static int[] filled(int length, int color) { int[] pixels = new int[length]; Arrays.fill(pixels, color); return pixels; }
}
