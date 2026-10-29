package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRenderStyle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRegionImageTest {
    @TempDir
    Path tempDir;

    @Test
    void publishesPartialRegionAndPreservesUntouchedPixelsOnLaterRefresh() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);

        image.putChunkPixels(0, 0, chunkPixels(0xFF114477));

        assertFalse(image.complete());
        assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 10L));
        BufferedImage partial = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).orElseThrow()));
        assertEquals(0xFF114477, partial.getRGB(0, 0));
        assertEquals(0x00000000, partial.getRGB(511, 511));
        MarketWebMapRegionImage refresh = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);
        refresh.putChunkPixels(31, 31, chunkPixels(0xFF778811));
        assertTrue(refresh.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 15L));
        BufferedImage merged = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).orElseThrow()));
        assertEquals(0xFF114477, merged.getRGB(0, 0), "unrendered chunks must retain the previous image");
        assertEquals(0xFF778811, merged.getRGB(511, 511));
        BufferedImage partialZoom = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 1, 0, 0).orElseThrow()));
        assertEquals(0xFF114477, partialZoom.getRGB(0, 0));
        assertEquals(0xFF778811, partialZoom.getRGB(255, 255));

        MarketWebMapRegionImage full = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);
        for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                full.putChunkPixels(chunkX, chunkZ, chunkPixels(colorFor(chunkX, chunkZ)));
            }
        }

        assertTrue(full.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 20L));

        BufferedImage base = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).orElseThrow()));
        assertEquals(512, base.getWidth());
        assertEquals(512, base.getHeight());
        assertEquals(colorFor(0, 0), base.getRGB(0, 0));
        assertEquals(colorFor(31, 31), base.getRGB(511, 511));

        BufferedImage zoomOne = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 1, 0, 0).orElseThrow()));
        assertEquals(colorFor(0, 0), zoomOne.getRGB(0, 0));
        assertEquals(colorFor(18, 18), zoomOne.getRGB(150, 150));
    }

    @Test
    void completeRegionCanPublishWhenSomeChunksWereUnrenderable() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);

        for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                if (chunkX == 31 && chunkZ == 31) {
                    image.markChunkSkipped(chunkX, chunkZ);
                } else {
                    image.putChunkPixels(chunkX, chunkZ, chunkPixels(colorFor(chunkX, chunkZ)));
                }
            }
        }

        assertTrue(image.complete());
        assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 30L));

        BufferedImage base = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).orElseThrow()));
        assertEquals(colorFor(0, 0), base.getRGB(0, 0));
        assertEquals(0x00000000, base.getRGB(511, 511));
    }

    @Test
    void rendererUsesContinuousLastYInsteadOfPerChunkReliefBase() {
        MarketWebMapRegionRenderer renderer = new MarketWebMapRegionRenderer();
        int[] lastY = new int[MarketWebMapConstants.CHUNK_SIZE];
        Arrays.fill(lastY, 70);
        int base = 0xFF669966;
        MarketWebMapChunkSnapshot snapshot = flatSnapshot(0, 0, 70, 0, base);

        int[] pixels = renderer.renderChunk(snapshot, lastY);

        assertEquals(RoadMapRenderStyle.styleTerrainByDelta(base, 0), pixels[0]);
        assertEquals(70, lastY[0]);
    }

    @Test
    void zoomTilesSampleExactPixelCentersAcrossPositiveAndNegativeRegions() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        for (int[] region : new int[][]{{0, 0}, {-3, -5}}) {
            int regionX = region[0];
            int regionZ = region[1];
            MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, regionX, regionZ);
            for (int localZ = 0; localZ < 32; localZ++) {
                for (int localX = 0; localX < 32; localX++) {
                    int chunkX = regionX * 32 + localX;
                    int chunkZ = regionZ * 32 + localZ;
                    assertTrue(image.putChunkPixels(chunkX, chunkZ, coordinatePixels(chunkX, chunkZ)));
                }
            }
            assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1L));

            for (int zoom = 1; zoom <= 4; zoom++) {
                int scale = 1 << zoom;
                int size = 512 / scale;
                int baseX = Math.floorMod(regionX, scale) * size;
                int baseZ = Math.floorMod(regionZ, scale) * size;
                int[] tile = cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, zoom,
                        Math.floorDiv(regionX, scale), Math.floorDiv(regionZ, scale));
                int[] expected = new int[size * size];
                int[] actual = new int[expected.length];
                for (int z = 0; z < size; z++) {
                    for (int x = 0; x < size; x++) {
                        expected[z * size + x] = coordinateColor(regionX * 512 + x * scale + scale / 2,
                                regionZ * 512 + z * scale + scale / 2);
                        actual[z * size + x] = tile[(baseZ + z) * 512 + baseX + x];
                    }
                }
                assertArrayEquals(expected, actual, "pixel centers at zoom " + zoom + " region " + regionX + "," + regionZ);
            }
        }
    }

    @Test
    void invalidPixelCenterStillUsesAvailablePartialRegionData() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, -1, -1);
        int[] partial = chunkPixels(Integer.MIN_VALUE);
        partial[0] = 0xFF3388AA;
        assertTrue(image.putChunkPixels(-32, -32, partial));
        assertTrue(image.writeDirty(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 1L));

        for (int zoom = 1; zoom <= 4; zoom++) {
            int scale = 1 << zoom;
            int offset = Math.floorMod(-1, scale) * (512 / scale);
            int[] tile = cache.readSquareTilePixels(MarketWebMapConstants.OVERWORLD, zoom, -1, -1);
            assertEquals(0xFF3388AA, tile[offset * 512 + offset], "fallback must avoid a hole at zoom " + zoom);
        }
    }

    private static int[] coordinatePixels(int chunkX, int chunkZ) {
        int[] pixels = new int[16 * 16];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                pixels[z * 16 + x] = coordinateColor(chunkX * 16 + x, chunkZ * 16 + z);
            }
        }
        return pixels;
    }

    private static int coordinateColor(int x, int z) {
        return 0xFF000000 | ((x & 1023) << 10) | (z & 1023);
    }

    private static int[] chunkPixels(int color) {
        int[] pixels = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static int colorFor(int chunkX, int chunkZ) {
        return 0xFF000000 | ((chunkX * 7 + 20) << 16) | ((chunkZ * 7 + 20) << 8) | 0x33;
    }

    private static MarketWebMapChunkSnapshot flatSnapshot(int chunkX, int chunkZ, int surfaceY, int reliefBaseY, int baseArgb) {
        RoadMapColumnSample[] samples = new RoadMapColumnSample[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            for (int x = 0; x < MarketWebMapConstants.CHUNK_SIZE; x++) {
                samples[z * MarketWebMapConstants.CHUNK_SIZE + x] = new RoadMapColumnSample(
                        chunkX * MarketWebMapConstants.CHUNK_SIZE + x,
                        surfaceY,
                        chunkZ * MarketWebMapConstants.CHUNK_SIZE + z,
                        baseArgb,
                        false,
                        0,
                        reliefBaseY);
            }
        }
        return new MarketWebMapChunkSnapshot(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, samples);
    }
}
