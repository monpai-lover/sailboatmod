package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.client.map.SharedMapClientState;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RoadPlannerPredictionLayerTest {
    @TempDir Path root;

    @Test
    void predictionIsVisibleWithoutMarkingActualCoverageAndRealChunksAlwaysWin() throws Exception {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(root.toFile(), "earth", "minecraft:overworld")) {
            SharedMapClientState state = new SharedMapClientState(manager);
            assertEquals(1, patch(state, "earth", "minecraft:overworld", -1, -1, 0, 0, 256, 256, solid(256, 0xFF116633), true));
            RoadPlannerTile tile = manager.getOrCreateTile(-1, -1, MapLod.LOD_1);
            assertEquals(0xFF116633, display(tile)[0]);
            assertTrue(tile.isLoadingImage(), "prediction must not masquerade as a completed actual image");
            assertFalse(state.isRendered("minecraft:overworld", -1, -1));
            assertEquals(1, patch(state, "earth", "minecraft:overworld", -1, -1, 240, 240, 16, 16, solid(16, 0xFF884422), false));
            assertTrue(state.isRendered("minecraft:overworld", -1, -1));
            assertFalse(state.isRendered("minecraft:overworld", -2, -1));
            assertEquals(0xFF884422, display(tile)[240 * 256 + 240]);
            assertEquals(0xFF116633, display(tile)[0]);

            patch(state, "earth", "minecraft:overworld", -1, -1, 0, 0, 256, 256, solid(256, 0xFF2288CC), true);
            assertEquals(0xFF884422, display(tile)[240 * 256 + 240], "late predictions cannot replace actual blocks");
            assertEquals(0xFF2288CC, display(tile)[0]);
            assertEquals(0, tile.copyPixels()[0], "actual pixels saved to disk must not include prediction");
            assertEquals(0xFF884422, tile.copyPixels()[240 * 256 + 240]);
        }
    }

    @Test
    void predictionNeverWritesActualCacheAndForeignWorldCannotMarkCoverage() throws Exception {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(root.toFile(), "earth", "minecraft:overworld")) {
            SharedMapClientState state = new SharedMapClientState(manager);
            patch(state, "earth", "minecraft:overworld", 0, 0, 0, 0, 256, 256, solid(256, 0xFF116633), true);
            try (var files = Files.walk(root)) {
                assertEquals(0, files.filter(path -> path.toString().endsWith(".png") && !path.toString().contains("prediction")).count());
            }
            assertEquals(0, patch(state, "other-world", "minecraft:overworld", 0, 0, 0, 0, 16, 16, solid(16, 0xFF884422), false));
            assertEquals(0, patch(state, "earth", "minecraft:the_nether", 0, 0, 0, 0, 16, 16, solid(16, 0xFF884422), false));
            assertFalse(state.isRendered("minecraft:overworld", 0, 0));
            assertFalse(state.isRendered("minecraft:the_nether", 0, 0));
        }
    }

    @Test
    void unknownActualPixelsStayTransparentAbovePredictionAndInvalidPatchesAreRejected() throws Exception {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(root.toFile(), "earth", "minecraft:overworld")) {
            SharedMapClientState state = new SharedMapClientState(manager);
            RoadPlannerTile tile = manager.getOrCreateTile(0, 0, MapLod.LOD_1);
            assertTrue(Arrays.stream(display(tile)).allMatch(pixel -> pixel == 0), "opaque checkerboards cannot cover the underlay");
            patch(state, "earth", "minecraft:overworld", 0, 0, 0, 0, 256, 256, solid(256, 0xFF116633), true);
            assertEquals(0, patch(state, "earth", "minecraft:overworld", 0, 0, 255, 255, 16, 16, solid(16, 0xFF884422), false));
            assertEquals(0xFF116633, display(tile)[255 * 256 + 255]);
            assertFalse(state.isRendered("minecraft:overworld", 15, 15));
        }
    }

    private static int patch(SharedMapClientState state, String world, String dimension, int tileX, int tileZ,
                             int x, int z, int width, int height, int[] pixels, boolean prediction) throws Exception {
        Method method;
        try {
            method = SharedMapClientState.class.getMethod("applyTerrainPatch", String.class, String.class,
                    int.class, int.class, int.class, int.class, int.class, int.class, int[].class, boolean.class);
        } catch (NoSuchMethodException missing) {
            return fail("shared map needs a source-aware terrain patch entry point");
        }
        return (int) method.invoke(state, world, dimension, tileX, tileZ, x, z, width, height, pixels, prediction);
    }

    private static int[] display(RoadPlannerTile tile) throws Exception {
        try {
            return (int[]) RoadPlannerTile.class.getMethod("copyDisplayPixels").invoke(tile);
        } catch (NoSuchMethodException missing) {
            return fail("display pixels must composite prediction beneath separate actual pixels");
        }
    }

    private static int[] solid(int size, int color) {
        int[] pixels = new int[size * size];
        Arrays.fill(pixels, color);
        return pixels;
    }

    @Test
    void legacyOpaqueTilesCannotHideTheNewPredictionLayer() throws Exception {
        Path legacy = root.resolve("earth/minecraft_overworld/lod_1/0_0.png");
        Files.createDirectories(legacy.getParent());
        try (var image = new com.mojang.blaze3d.platform.NativeImage(256, 256, true)) {
            for (int z = 0; z < 256; z++) for (int x = 0; x < 256; x++) image.setPixelRGBA(x, z, 0xFF2A2A2A);
            image.writeToFile(legacy);
        }
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(root.toFile(), "earth", "minecraft:overworld")) {
            assertTrue(Arrays.stream(display(manager.getOrCreateTile(0, 0))).allMatch(color -> color == 0),
                    "the source-aware actual cache needs a separate format version from legacy opaque images");
        }
    }

    @Test
    void panningCannotGrowTheNativeTileAndPredictionTextureCacheWithoutBound() {
        try (RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(root.toFile(), "earth", "minecraft:overworld")) {
            for (int x = 0; x < 260; x++) manager.getOrCreateTile(x, 0);
            assertTrue(manager.loadedTileCount() <= 256, "old offscreen native textures must be released");
        }
    }
}
