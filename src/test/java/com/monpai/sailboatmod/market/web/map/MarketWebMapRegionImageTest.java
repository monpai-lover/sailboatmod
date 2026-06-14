package com.monpai.sailboatmod.market.web.map;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRegionImageTest {
    @TempDir
    Path tempDir;

    @Test
    void writesRegionAndPyramidOnlyAfterAllChunksArePresent() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        MarketWebMapRegionImage image = new MarketWebMapRegionImage(MarketWebMapConstants.OVERWORLD, 0, 0);

        image.putChunkPixels(0, 0, chunkPixels(0xFF114477));

        assertFalse(image.writeIfComplete(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 10L));
        assertFalse(cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).isPresent());

        for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                image.putChunkPixels(chunkX, chunkZ, chunkPixels(colorFor(chunkX, chunkZ)));
            }
        }

        assertTrue(image.writeIfComplete(cache, MarketWebMapTileQuality.SERVER_REGION_SCAN, 20L));

        BufferedImage base = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 0, 0, 0).orElseThrow()));
        assertEquals(512, base.getWidth());
        assertEquals(512, base.getHeight());
        assertEquals(colorFor(0, 0), base.getRGB(0, 0));
        assertEquals(colorFor(31, 31), base.getRGB(511, 511));

        BufferedImage zoomOne = ImageIO.read(new ByteArrayInputStream(
                cache.readSquareTile(MarketWebMapConstants.OVERWORLD, 1, 0, 0).orElseThrow()));
        assertEquals(colorFor(0, 0), zoomOne.getRGB(0, 0));
        assertEquals(0x00000000, zoomOne.getRGB(300, 300));
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
