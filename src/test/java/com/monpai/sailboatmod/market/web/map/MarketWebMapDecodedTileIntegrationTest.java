package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.*;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapDecodedTileIntegrationTest {
    private static final String WORLD = MarketWebMapConstants.OVERWORLD;
    @TempDir Path directory;

    @Test
    void warmUnchangedWriteDoesNotCreateAPngWriterOrPublishAnotherChange() {
        var cache = new MarketWebMapTileCache(directory.resolve("warm"));
        int[] pixels = pixels(0xFF225588);
        assertTrue(cache.writeSquareTilePixels(WORLD, 0, 0, 0, pixels));
        assertArrayEquals(pixels, cache.readSquareTilePixels(WORLD, 0, 0, 0));
        long revision = cache.changeJournal().revision();
        var attempts = new AtomicInteger();
        var spi = new FailingPngWriterSpi(attempts);
        IIORegistry registry = IIORegistry.getDefaultInstance();
        var existing = new ArrayList<ImageWriterSpi>();
        registry.getServiceProviders(ImageWriterSpi.class, true).forEachRemaining(existing::add);
        registry.registerServiceProvider(spi, ImageWriterSpi.class);
        for (ImageWriterSpi other : existing) registry.setOrdering(ImageWriterSpi.class, spi, other);
        try {
            assertDoesNotThrow(() -> assertTrue(cache.writeSquareTilePixels(WORLD, 0, 0, 0, pixels.clone())),
                    "validated cached pixels must bypass the PNG encoder");
            assertEquals(0, attempts.get());
            assertEquals(revision, cache.changeJournal().revision());
        } finally {
            registry.deregisterServiceProvider(spi, ImageWriterSpi.class);
        }
    }

    @Test
    void cachedPixelsCannotBeChangedThroughWriteInputsOrReadResults() {
        var cache = new MarketWebMapTileCache(directory.resolve("mutable"));
        int[] pixels = pixels(0xFF336699);
        cache.writeSquareTilePixels(WORLD, 0, 0, 0, pixels);
        pixels[0] = 0;
        int[] first = cache.readSquareTilePixels(WORLD, 0, 0, 0);
        assertEquals(0xFF336699, first[0]);
        first[0] = 0;
        assertEquals(0xFF336699, cache.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
    }

    @Test
    void externalRewriteWithTheSameModificationTimeCannotServeStalePixels() throws Exception {
        var cache = new MarketWebMapTileCache(directory.resolve("external"));
        cache.writeSquareTilePixels(WORLD, 0, -1, 2, pixels(0xFF112233));
        assertEquals(0xFF112233, cache.readSquareTilePixels(WORLD, 0, -1, 2)[0]);
        Path png = cache.squareTileDir(0).resolve("-1_2.png");
        FileTime originalTime = Files.getLastModifiedTime(png);
        Files.write(png, externalPng(0xFF99AABB));
        Files.setLastModifiedTime(png, originalTime);
        assertEquals(originalTime, Files.getLastModifiedTime(png));
        assertEquals(0xFF99AABB, cache.readSquareTilePixels(WORLD, 0, -1, 2)[0]);
        Files.delete(png);
        assertEquals(0, cache.readSquareTilePixels(WORLD, 0, -1, 2)[0]);
        Files.write(png, externalPng(0xFF778899));
        Files.setLastModifiedTime(png, originalTime);
        assertEquals(0xFF778899, cache.readSquareTilePixels(WORLD, 0, -1, 2)[0]);
    }

    @Test
    void externalRewriteAlsoInvalidatesTheWarmUnchangedWriteShortcut() throws Exception {
        var cache = new MarketWebMapTileCache(directory.resolve("write_after_external_change"));
        int[] original = pixels(0xFF112233);
        cache.writeSquareTilePixels(WORLD, 0, 0, 0, original);
        Path png = cache.squareTileDir(0).resolve("0_0.png");
        FileTime originalTime = Files.getLastModifiedTime(png);
        Files.write(png, externalPng(0xFF445566));
        Files.setLastModifiedTime(png, originalTime);
        long revision = cache.changeJournal().revision();
        assertTrue(cache.writeSquareTilePixels(WORLD, 0, 0, 0, original));
        assertTrue(cache.changeJournal().revision() > revision, "the requested old pixels must replace the external PNG");
        assertEquals(0xFF112233, cache.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
    }

    @Test
    void coldExternalTileDecodesWithoutAnImageIoTemporaryDirectory() throws Exception {
        var cache = new MarketWebMapTileCache(directory.resolve("cold_memory_decode"));
        Files.createDirectories(cache.squareTileDir(0));
        Files.write(cache.squareTileDir(0).resolve("0_0.png"), externalPng(0xA05577AA));
        boolean oldUseCache = ImageIO.getUseCache();
        var oldDirectory = ImageIO.getCacheDirectory();
        Path unavailable = Files.createDirectory(directory.resolve("imageio_temp"));
        try {
            ImageIO.setUseCache(true);
            ImageIO.setCacheDirectory(unavailable.toFile());
            Files.delete(unavailable);
            assertArrayEquals(pixels(0xA05577AA), cache.readSquareTilePixels(WORLD, 0, 0, 0));
            assertTrue(ImageIO.getUseCache());
            assertEquals(unavailable.toFile(), ImageIO.getCacheDirectory());
        } finally {
            ImageIO.setCacheDirectory(oldDirectory);
            ImageIO.setUseCache(oldUseCache);
        }
    }

    @Test
    void equalCoordinatesInDifferentWorldsRemainIsolatedAndFailedWritesDoNotPoisonRecovery() throws Exception {
        var first = new MarketWebMapTileCache(directory.resolve("world_a"));
        var second = new MarketWebMapTileCache(directory.resolve("world_b"));
        first.writeSquareTilePixels(WORLD, 0, 0, 0, pixels(0xFF112233));
        second.writeSquareTilePixels(WORLD, 0, 0, 0, pixels(0xFF445566));
        assertEquals(0xFF112233, first.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
        assertEquals(0xFF445566, second.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
        Path blocked = first.squareTileDir(0);
        Files.delete(blocked.resolve("0_0.png"));
        Files.delete(blocked);
        Files.writeString(blocked, "blocking the previously cached destination");
        long revision = first.changeJournal().revision();
        assertThrows(UncheckedIOException.class, () -> first.writeSquareTilePixels(WORLD, 0, 0, 0, pixels(0xFF778899)));
        assertEquals(revision, first.changeJournal().revision());
        Files.delete(blocked);
        assertTrue(first.writeSquareTilePixels(WORLD, 0, 0, 0, pixels(0xFF778899)));
        assertEquals(0xFF778899, first.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
        assertEquals(0xFF445566, second.readSquareTilePixels(WORLD, 0, 0, 0)[0]);
    }

    private static int[] pixels(int color) {
        int[] pixels = new int[512 * 512];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static byte[] externalPng(int color) throws IOException {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 512, 512, pixels(color), 0, 512);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "png", bytes));
        return bytes.toByteArray();
    }

    private static final class FailingPngWriterSpi extends ImageWriterSpi {
        private final AtomicInteger attempts;
        FailingPngWriterSpi(AtomicInteger attempts) {
            super("SailboatMod test", "1", new String[]{"png", "PNG"}, new String[]{"png"}, new String[]{"image/png"},
                    FailingWriter.class.getName(), new Class<?>[]{ImageOutputStream.class}, null,
                    false, null, null, null, null, false, null, null, null, null);
            this.attempts = attempts;
        }
        @Override public boolean canEncodeImage(ImageTypeSpecifier type) { return true; }
        @Override public String getDescription(Locale locale) { return "Detects unexpected PNG encoder creation"; }
        @Override public ImageWriter createWriterInstance(Object extension) throws IOException {
            attempts.incrementAndGet();
            return new FailingWriter(this);
        }
    }

    private static final class FailingWriter extends ImageWriter {
        FailingWriter(ImageWriterSpi spi) { super(spi); }
        @Override public IIOMetadata getDefaultStreamMetadata(ImageWriteParam param) { return null; }
        @Override public IIOMetadata getDefaultImageMetadata(ImageTypeSpecifier type, ImageWriteParam param) { return null; }
        @Override public IIOMetadata convertStreamMetadata(IIOMetadata data, ImageWriteParam param) { return null; }
        @Override public IIOMetadata convertImageMetadata(IIOMetadata data, ImageTypeSpecifier type, ImageWriteParam param) { return null; }
        @Override public void write(IIOMetadata metadata, IIOImage image, ImageWriteParam param) throws IOException {
            throw new IOException("the unchanged fast path must not invoke PNG encoding");
        }
    }
}
