package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapPngCodecTest {
    @Test
    void preservesEveryAlphaAndColorBitAndExistingCompressedBytes() throws Exception {
        int[] pixels = new Random(9128).ints(32 * 32).toArray();
        pixels[0] = 0x00123456;
        pixels[1] = 0x80123456;
        byte[] bytes = MarketWebMapPngCodec.encode(32, 32, pixels);
        assertArrayEquals(pixels, MarketWebMapPngCodec.decode(bytes, 32, 32).orElseThrow());
        var legacyImage = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        legacyImage.setRGB(0, 0, 32, 32, pixels, 0, 32);
        var legacy = new ByteArrayOutputStream();
        ImageIO.write(legacyImage, "png", legacy);
        assertArrayEquals(legacy.toByteArray(), bytes, "compression and file size must stay compatible");
    }

    @Test
    void wrongDimensionsAndBrokenPngReturnNoPixels() {
        byte[] png = MarketWebMapPngCodec.encode(4, 8, new int[32]);
        assertTrue(MarketWebMapPngCodec.decode(png, 8, 4).isEmpty());
        assertTrue(MarketWebMapPngCodec.decode(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0, 0, 0, 0}, 512, 512).isEmpty());
        assertTrue(MarketWebMapPngCodec.decode(null, 512, 512).isEmpty());
        assertEquals(0, MarketWebMapPngCodec.encode(4, 8, new int[31]).length);
    }

    @Test
    void concurrentCallsOwnTheirWriterAndPixelBuffers() throws Exception {
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 16; i++) {
                int seed = i;
                jobs.add(() -> {
                    int[] pixels = new Random(seed).ints(64 * 64).toArray();
                    assertArrayEquals(pixels, MarketWebMapPngCodec.decode(
                            MarketWebMapPngCodec.encode(64, 64, pixels), 64, 64).orElseThrow());
                    return null;
                });
            }
            for (var result : workers.invokeAll(jobs)) result.get();
        }
    }
}
