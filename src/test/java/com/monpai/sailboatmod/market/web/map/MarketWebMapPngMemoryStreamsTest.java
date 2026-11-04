package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapPngMemoryStreamsTest {
    @TempDir Path temp;

    @Test
    void encodingDoesNotRequireAnImageIoTemporaryDirectory() throws Exception {
        boolean oldUseCache = ImageIO.getUseCache();
        File oldDirectory = ImageIO.getCacheDirectory();
        Path cacheDirectory = Files.createDirectory(temp.resolve("imageio"));
        try {
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(cacheDirectory.toFile());
            Files.delete(cacheDirectory);
            int[] pixels = new int[256 * 256];
            Arrays.fill(pixels, 0xA07845FE);
            byte[] png = MarketWebMapTileCache.encodePng(256, 256, pixels);
            assertTrue(png.length > 8, "rendering must not need a temporary filesystem cache");
            assertTrue(ImageIO.getUseCache(), "must preserve other mods' global ImageIO setting");
            assertEquals(cacheDirectory.toFile(), ImageIO.getCacheDirectory());
        } finally {
            ImageIO.setCacheDirectory(oldDirectory);
            ImageIO.setUseCache(oldUseCache);
        }
    }

    @Test
    void readingAnExistingTileDoesNotRequireAnImageIoTemporaryDirectory() throws Exception {
        var cache = new MarketWebMapTileCache(temp.resolve("world"));
        int[] pixels = new int[512 * 512];
        Arrays.fill(pixels, 0xFF345678);
        assertTrue(cache.writeSquareTilePixels("minecraft:overworld", 0, 0, 0, pixels));
        boolean oldUseCache = ImageIO.getUseCache();
        File oldDirectory = ImageIO.getCacheDirectory();
        Path cacheDirectory = Files.createDirectory(temp.resolve("imageio"));
        try {
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(cacheDirectory.toFile());
            Files.delete(cacheDirectory);
            assertArrayEquals(pixels, cache.readSquareTilePixels("minecraft:overworld", 0, 0, 0));
            assertTrue(ImageIO.getUseCache());
        } finally {
            ImageIO.setCacheDirectory(oldDirectory);
            ImageIO.setUseCache(oldUseCache);
        }
    }
}
