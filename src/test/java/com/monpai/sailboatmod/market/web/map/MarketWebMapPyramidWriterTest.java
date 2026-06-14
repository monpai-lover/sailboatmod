package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
