package com.monpai.sailboatmod.client.marketweb;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.market.web.map.MarketWebMapConstants;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.UUID;

import javax.imageio.ImageIO;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapTileUploadDedupeTest {
    @Test
    void rejectsDuplicateTileContentUntilEvicted() {
        MarketWebMapTileUploadDedupe dedupe = new MarketWebMapTileUploadDedupe(2);
        MarketWebMapTileUploadDedupe.Key first = key(1, 2, 10, 20);
        MarketWebMapTileUploadDedupe.Key second = key(3, 4, 30, 40);
        MarketWebMapTileUploadDedupe.Key third = key(5, 6, 50, 60);

        assertTrue(dedupe.remember(first));
        assertFalse(dedupe.remember(first));
        assertTrue(dedupe.remember(second));
        assertTrue(dedupe.remember(third));

        assertTrue(dedupe.remember(first));
    }

    @Test
    void allowsSameTileWhenContentChanges() {
        MarketWebMapTileUploadDedupe dedupe = new MarketWebMapTileUploadDedupe(4);
        MarketWebMapTileUploadDedupe.Key original = key(7, 8, 70, 80);
        MarketWebMapTileUploadDedupe.Key changedPixels = key(7, 8, 70, 81);

        assertTrue(dedupe.remember(original));
        assertFalse(dedupe.remember(original));
        assertTrue(dedupe.remember(changedPixels));
        assertFalse(dedupe.remember(changedPixels));
    }

    @Test
    void clearAllowsReuploadForNewConnection() {
        MarketWebMapTileUploadDedupe dedupe = new MarketWebMapTileUploadDedupe(4);
        MarketWebMapTileUploadDedupe.Key key = key(9, 10, 90, 100);

        assertTrue(dedupe.remember(key));
        assertFalse(dedupe.remember(key));

        dedupe.clear();

        assertTrue(dedupe.remember(key));
    }

    @Test
    void partialTileUploadUsesMergedPixelsInsteadOfRawPartialPacket() {
        int[] rawPixels = pixels(0xFF000000);
        rawPixels[0] = 0xFF0000CC;
        boolean[] coverage = new boolean[rawPixels.length];
        coverage[0] = true;
        int[] mergedPixels = pixels(0xFF00AA00);
        mergedPixels[0] = 0xFF0000CC;

        int[] selected = MarketWebMapTileUploadClient.selectUploadPixelsForTest(packet(rawPixels, coverage), mergedPixels);

        assertArrayEquals(mergedPixels, selected);
    }

    @Test
    void partialTileUploadKeepsMergedArgbChannelsForWebPng() throws Exception {
        int[] rawPixels = pixels(0xFF000000);
        rawPixels[0] = 0xFF0000CC;
        boolean[] coverage = new boolean[rawPixels.length];
        coverage[0] = true;
        int[] mergedPixels = pixels(0xFF00AA00);
        mergedPixels[0] = 0xFF0000CC;

        byte[] png = MarketWebMapTileUploadClient.encodeSelectedUploadPngForTest(packet(rawPixels, coverage), mergedPixels);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(png.length > 0);
        assertTrue(image != null);
        assertTrue(image.getRGB(0, 0) == 0xFF0000CC);
        assertTrue(image.getRGB(32, 32) == 0xFF00AA00);
    }

    @Test
    void partialTileUploadSkipsWhenMergedTileIsUnavailable() {
        int[] rawPixels = pixels(0xFF000000);
        rawPixels[0] = 0xFF0000CC;
        boolean[] coverage = new boolean[rawPixels.length];
        coverage[0] = true;

        int[] selected = MarketWebMapTileUploadClient.selectUploadPixelsForTest(packet(rawPixels, coverage), null);

        assertTrue(selected.length == 0);
    }

    @Test
    void fullTileUploadCanUseRawPacketWhenMergedTileIsUnavailable() {
        int[] rawPixels = pixels(0xFF336699);
        boolean[] coverage = new boolean[rawPixels.length];
        Arrays.fill(coverage, true);

        int[] selected = MarketWebMapTileUploadClient.selectUploadPixelsForTest(packet(rawPixels, coverage), null);

        assertArrayEquals(rawPixels, selected);
    }

    @Test
    void uploadPngEncodingConvertsNativeImageChannelsForWebTiles() throws Exception {
        int[] nativePixels = pixels(0xFFCC0000);

        byte[] png = MarketWebMapTileUploadClient.encodeUploadPngForTest(
                MarketWebMapConstants.TILE_SIZE,
                MarketWebMapConstants.TILE_SIZE,
                nativePixels);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(png.length > 0);
        assertTrue(image != null);
        assertTrue(image.getRGB(0, 0) == 0xFF0000CC);
    }

    @Test
    void fullTileUploadKeepsRawPacketArgbChannelsWhenMergedTileIsUnavailable() throws Exception {
        int[] rawPixels = pixels(0xFF0000CC);
        boolean[] coverage = new boolean[rawPixels.length];
        Arrays.fill(coverage, true);
        RoadPlannerMapTileSyncPacket packet = packet(rawPixels, coverage);

        byte[] png = MarketWebMapTileUploadClient.encodeSelectedUploadPngForTest(packet, null);

        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        assertTrue(png.length > 0);
        assertTrue(image != null);
        assertTrue(image.getRGB(0, 0) == 0xFF0000CC);
    }

    private static MarketWebMapTileUploadDedupe.Key key(int tileX, int tileZ, int coverageHash, int pixelHash) {
        return new MarketWebMapTileUploadDedupe.Key(
                "minecraft:overworld",
                MapLod.LOD_1,
                tileX,
                tileZ,
                coverageHash,
                pixelHash
        );
    }

    private static int[] pixels(int color) {
        int[] pixels = new int[256 * 256];
        Arrays.fill(pixels, color);
        return pixels;
    }

    private static RoadPlannerMapTileSyncPacket packet(int[] pixels, boolean[] coverageMask) {
        return new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                256,
                256,
                pixels,
                coverageMask
        );
    }
}
