package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapPyramidWriterTest {
    @TempDir
    Path tempDir;

    @Test
    void mergeChunkWritesBaseAndScaledTiles() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] chunk = new int[16 * 16];
        Arrays.fill(chunk, 0xFF3F76C4);

        assertTrue(cache.mergeChunkPyramid("minecraft:overworld", 33, -1, chunk,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 100L));

        byte[] baseBytes = cache.readSquareTile("minecraft:overworld", 0, 1, -1).orElseThrow();
        BufferedImage base = ImageIO.read(new ByteArrayInputStream(baseBytes));
        assertEquals(512, base.getWidth());
        assertEquals(512, base.getHeight());
        assertEquals(0xFF3F76C4, base.getRGB(16, 496));

        byte[] zoomBytes = cache.readSquareTile("minecraft:overworld", 1, 0, -1).orElseThrow();
        BufferedImage zoom = ImageIO.read(new ByteArrayInputStream(zoomBytes));
        assertEquals(512, zoom.getWidth());
        assertEquals(512, zoom.getHeight());
        assertEquals(0xFF3F76C4, zoom.getRGB(264, 504));
    }

    @Test
    void scaledChunksUseExactPixelCentersAcrossPositiveAndNegativeCoordinates() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        for (int[] chunk : new int[][]{{33, -1}, {-33, -65}}) {
            int chunkX = chunk[0];
            int chunkZ = chunk[1];
            int[] pixels = new int[16 * 16];
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    pixels[z * 16 + x] = coordinateColor(chunkX * 16 + x, chunkZ * 16 + z);
                }
            }
            assertTrue(cache.mergeChunkPyramid("minecraft:overworld", chunkX, chunkZ, pixels,
                    MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L));

            for (int zoom = 1; zoom <= 4; zoom++) {
                int scale = 1 << zoom;
                int size = 16 / scale;
                int chunksPerTile = 32 * scale;
                int baseX = Math.floorMod(chunkX, chunksPerTile) * size;
                int baseZ = Math.floorMod(chunkZ, chunksPerTile) * size;
                int[] tile = cache.readSquareTilePixels("minecraft:overworld", zoom,
                        Math.floorDiv(chunkX, chunksPerTile), Math.floorDiv(chunkZ, chunksPerTile));
                int[] expected = new int[size * size];
                int[] actual = new int[expected.length];
                for (int z = 0; z < size; z++) {
                    for (int x = 0; x < size; x++) {
                        expected[z * size + x] = coordinateColor(chunkX * 16 + x * scale + scale / 2,
                                chunkZ * 16 + z * scale + scale / 2);
                        actual[z * size + x] = tile[(baseZ + z) * 512 + baseX + x];
                    }
                }
                assertArrayEquals(expected, actual, "pixel centers at zoom " + zoom + " chunk " + chunkX + "," + chunkZ);
            }
        }
    }

    @Test
    void invalidPixelCenterUsesValidFallbackInsteadOfOverwritingWithMissingPixels() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] partial = new int[16 * 16];
        Arrays.fill(partial, Integer.MIN_VALUE);
        partial[0] = 0xFF3388AA;
        partial[1] = 0;
        assertTrue(cache.mergeChunkPyramid("minecraft:overworld", -1, -1, partial,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L));

        for (int zoom = 1; zoom <= 4; zoom++) {
            int scale = 1 << zoom;
            int base = Math.floorMod(-1, 32 * scale) * (16 / scale);
            int[] tile = cache.readSquareTilePixels("minecraft:overworld", zoom, -1, -1);
            assertEquals(0xFF3388AA, tile[base * 512 + base], "missing center must not hide valid data at zoom " + zoom);
        }
    }

    @Test
    void entirelyMissingScaledBlockPreservesPreviouslyRenderedPixel() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] initial = new int[16 * 16];
        Arrays.fill(initial, 0xFF3388AA);
        assertTrue(cache.mergeChunkPyramid("minecraft:overworld", 0, 0, initial,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L));
        int[] partial = new int[16 * 16];
        Arrays.fill(partial, Integer.MIN_VALUE);
        partial[15 * 16 + 15] = 0xFFAA7733;
        assertTrue(cache.mergeChunkPyramid("minecraft:overworld", 0, 0, partial,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 2L));

        for (int zoom = 1; zoom <= 3; zoom++) {
            int[] tile = cache.readSquareTilePixels("minecraft:overworld", zoom, 0, 0);
            assertEquals(0xFF3388AA, tile[0], "an entirely missing block must retain its prior pixel at zoom " + zoom);
        }
    }

    private static int coordinateColor(int x, int z) {
        return 0xFF000000 | ((x & 1023) << 10) | (z & 1023);
    }
}
