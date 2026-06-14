package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Optional;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapTileCacheTest {
    @TempDir
    Path tempDir;

    @Test
    void missingTileReturnsEmptyWithoutCreatingFiles() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);

        Optional<byte[]> missing = cache.readPng("minecraft:overworld", 1, -2);

        assertFalse(missing.isPresent());
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/1_-2.png")));
    }

    @Test
    void storesAndReadsPngBytes() {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] pixels = new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE];
        java.util.Arrays.fill(pixels, 0xFF112233);
        byte[] png = MarketWebMapTileCache.encodePng(MarketWebMapConstants.TILE_SIZE, MarketWebMapConstants.TILE_SIZE, pixels);

        assertTrue(cache.writePng("minecraft:overworld", 3, 4, png));

        assertArrayEquals(png, cache.readPng("minecraft:overworld", 3, 4).orElseThrow());
    }

    @Test
    void encodePngKeepsArgbChannelsForServerTiles() throws Exception {
        byte[] png = solidPng(0xFF0000CC);

        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
    }

    @Test
    void encodeNativeImagePngConvertsNativeChannelsForWebTiles() throws Exception {
        int[] pixels = new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE];
        java.util.Arrays.fill(pixels, 0xFFCC0000);

        byte[] png = MarketWebMapTileCache.encodeNativeImagePng(
                MarketWebMapConstants.TILE_SIZE,
                MarketWebMapConstants.TILE_SIZE,
                pixels);

        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
    }

    @Test
    void duplicatePngWriteKeepsExistingFileUntouched() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        byte[] png = solidPng(0xFF445566);
        Path path = tempDir.resolve("overworld").resolve("lod_1").resolve("7_8.png");

        assertTrue(cache.writePng("minecraft:overworld", 7, 8, png));
        FileTime originalTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(path, originalTime);

        assertTrue(cache.writePng("minecraft:overworld", 7, 8, png));

        assertEquals(originalTime, Files.getLastModifiedTime(path));
        assertArrayEquals(png, Files.readAllBytes(path));
    }

    @Test
    void changedPngWriteReplacesExistingFile() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        byte[] first = solidPng(0xFF112233);
        byte[] second = solidPng(0xFFAA7733);
        Path path = tempDir.resolve("overworld").resolve("lod_1").resolve("9_10.png");

        assertTrue(cache.writePng("minecraft:overworld", 9, 10, first));
        FileTime originalTime = FileTime.fromMillis(1_700_000_000_000L);
        Files.setLastModifiedTime(path, originalTime);

        assertTrue(cache.writePng("minecraft:overworld", 9, 10, second));

        assertNotEquals(originalTime, Files.getLastModifiedTime(path));
        assertArrayEquals(second, Files.readAllBytes(path));
    }

    @Test
    void serverChunkMergeWritesExpectedSubregionAndPreservesOtherPixels() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        int[] chunkPixels = chunkPixels(0xFF0000CC);

        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 17, -1, chunkPixels,
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1_700_000_000_000L));

        byte[] png = cache.readPng("minecraft:overworld", 1, -1).orElseThrow();
        assertEquals(0x00000000, readArgb(png, 0, 0));
        assertEquals(0xFF0000CC, readArgb(png, 16, 240));
        assertEquals(MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                cache.readMetadata("minecraft:overworld", 1, -1).orElseThrow().sourceAt(1, 15));
    }

    @Test
    void serverLoadedChunkOverwritesClientUploadButClientUploadDoesNotOverwriteServerChunk() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFF0000CC),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L));

        byte[] clientPng = solidPng(0xFF22AA44);
        assertTrue(cache.writePng("minecraft:overworld", 0, 0, clientPng, MarketWebMapTileQuality.CLIENT_UPLOAD, 2L));

        byte[] png = cache.readPng("minecraft:overworld", 0, 0).orElseThrow();
        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
        assertEquals(0xFF22AA44, readArgb(png, 32, 0));
        assertEquals(MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                cache.readMetadata("minecraft:overworld", 0, 0).orElseThrow().sourceAt(0, 0));
        assertEquals(MarketWebMapTileQuality.CLIENT_UPLOAD,
                cache.readMetadata("minecraft:overworld", 0, 0).orElseThrow().sourceAt(2, 0));
    }

    @Test
    void newerServerRenderVersionRefreshesExistingServerChunk() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFFAA0000),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L, 1));

        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFF0000CC),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 2L, MarketWebMapTileCache.RENDER_VERSION));

        byte[] png = cache.readPng("minecraft:overworld", 0, 0).orElseThrow();
        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
        assertEquals(MarketWebMapTileCache.RENDER_VERSION,
                cache.readMetadata("minecraft:overworld", 0, 0).orElseThrow().rendererVersion());
    }

    @Test
    void noMetadataPngIsLegacyAndCanBeRepairedOrOverwrittenByServerOnly() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        byte[] legacy = solidPng(0xFFAA0000);

        assertTrue(cache.writePng("minecraft:overworld", 2, 3, legacy));
        assertEquals(MarketWebMapTileQuality.LEGACY_UNKNOWN,
                cache.readMetadata("minecraft:overworld", 2, 3).orElseThrow().sourceAt(0, 0));

        assertTrue(cache.writePng("minecraft:overworld", 2, 3, solidPng(0xFF00AA00),
                MarketWebMapTileQuality.CLIENT_UPLOAD, 2L));
        assertEquals(0xFFAA0000, readArgb(cache.readPng("minecraft:overworld", 2, 3).orElseThrow(), 0, 0));

        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 32, 48, chunkPixels(0xFF0000CC),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 3L));
        byte[] png = cache.readPng("minecraft:overworld", 2, 3).orElseThrow();
        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
        assertEquals(0xFFAA0000, readArgb(png, 32, 0));
    }

    @Test
    void legacyRepairWritesMetadataAndClearRemovesOnlyNoMetadataPngs() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.writePng("minecraft:overworld", 4, 5, solidPng(0xFF334455)));
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 80, 80, chunkPixels(0xFF778899),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 4L));

        assertEquals(1, cache.repairLegacyMetadata());
        assertTrue(Files.isRegularFile(tempDir.resolve("overworld/lod_1/4_5.json")));

        assertEquals(0, cache.clearLegacyPngs());
        Files.delete(tempDir.resolve("overworld/lod_1/4_5.json"));

        assertEquals(1, cache.clearLegacyPngs());
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/4_5.png")));
        assertTrue(Files.exists(tempDir.resolve("overworld/lod_1/5_5.png")));
    }

    @Test
    void clearClientUploadsPreservesServerChunksAndClearsClientChunks() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFF0000CC),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L));
        assertTrue(cache.writePng("minecraft:overworld", 0, 0, solidPng(0xFFCC0000),
                MarketWebMapTileQuality.CLIENT_UPLOAD, 2L));

        assertEquals(1, cache.clearClientUploadChunks());

        byte[] png = cache.readPng("minecraft:overworld", 0, 0).orElseThrow();
        assertEquals(0xFF0000CC, readArgb(png, 0, 0));
        assertEquals(0x00000000, readArgb(png, 32, 0));
        assertEquals(MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                cache.readMetadata("minecraft:overworld", 0, 0).orElseThrow().sourceAt(0, 0));
        assertEquals(MarketWebMapTileQuality.UNKNOWN,
                cache.readMetadata("minecraft:overworld", 0, 0).orElseThrow().sourceAt(2, 0));
    }

    @Test
    void clearClientUploadsDeletesTileWhenNoServerChunksRemain() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.writePng("minecraft:overworld", 2, 3, solidPng(0xFFCC0000),
                MarketWebMapTileQuality.CLIENT_UPLOAD, 1L));

        assertEquals(1, cache.clearClientUploadChunks());

        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/2_3.png")));
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/2_3.json")));
    }

    @Test
    void rendererVersionInvalidatesPreRegionCompletenessTiles() {
        assertTrue(MarketWebMapTileCache.RENDER_VERSION >= 5);
    }

    @Test
    void clearStaleServerChunksRemovesOldRendererVersionTiles() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFFCC0000),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L, 1));

        assertEquals(1, cache.clearStaleServerChunks());

        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/0_0.png")));
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/0_0.json")));
    }

    @Test
    void clearAllTilesDeletesCurrentVersionServerTilesAndMetadata() throws Exception {
        MarketWebMapTileCache cache = new MarketWebMapTileCache(tempDir);
        assertTrue(cache.mergeChunkArgb("minecraft:overworld", 0, 0, chunkPixels(0xFFCC0000),
                MarketWebMapTileQuality.SERVER_LOADED_CHUNK, 1L, MarketWebMapTileCache.RENDER_VERSION));
        assertTrue(cache.writePng("minecraft:overworld", 2, 3, solidPng(0xFF334455)));

        assertEquals(2, cache.clearAllTiles());

        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/0_0.png")));
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/0_0.json")));
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/2_3.png")));
        assertFalse(Files.exists(tempDir.resolve("overworld/lod_1/2_3.json")));
    }

    private static byte[] solidPng(int color) {
        int[] pixels = new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE];
        java.util.Arrays.fill(pixels, color);
        return MarketWebMapTileCache.encodePng(MarketWebMapConstants.TILE_SIZE, MarketWebMapConstants.TILE_SIZE, pixels);
    }

    private static int[] chunkPixels(int color) {
        int[] pixels = new int[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        java.util.Arrays.fill(pixels, color);
        return pixels;
    }

    private static int readArgb(byte[] png, int x, int y) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        return image.getRGB(x, y);
    }
}
