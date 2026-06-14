package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.network.packet.marketweb.MarketWebMapTileUploadPacket;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapTileUploadServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void rejectsNonOverworldUpload() {
        MarketWebMapTileUploadService.Result result = MarketWebMapTileUploadService.validateForTest(
                "minecraft:the_nether", MapLod.LOD_1, 0, 0, 256, 256, new int[256 * 256], new boolean[256 * 256]);

        assertEquals(MarketWebMapTileUploadService.Result.REJECTED_DIMENSION, result);
    }

    @Test
    void rejectsWrongPixelDimensions() {
        MarketWebMapTileUploadService.Result result = MarketWebMapTileUploadService.validateForTest(
                "minecraft:overworld", MapLod.LOD_1, 0, 0, 128, 128, new int[128 * 128], new boolean[128 * 128]);

        assertEquals(MarketWebMapTileUploadService.Result.REJECTED_SIZE, result);
    }

    @Test
    void clientUploadPathIsDisabledAndDoesNotWriteTiles() {
        int[] pixels = new int[MarketWebMapConstants.TILE_SIZE * MarketWebMapConstants.TILE_SIZE];
        Arrays.fill(pixels, 0xFF24486A);
        byte[] png = MarketWebMapTileCache.encodePng(
                MarketWebMapConstants.TILE_SIZE,
                MarketWebMapConstants.TILE_SIZE,
                pixels);
        int split = Math.max(1, png.length / 2);
        byte[] first = Arrays.copyOfRange(png, 0, split);
        byte[] second = Arrays.copyOfRange(png, split, png.length);
        UUID playerId = UUID.randomUUID();

        MarketWebMapTileUploadService.Result firstResult = MarketWebMapTileUploadService.acceptClientUploadForTest(
                tempDir,
                playerId,
                new MarketWebMapTileUploadPacket("minecraft:overworld", MapLod.LOD_1, 5, -6, 99, png.length, 0, 2, first),
                1_000L);
        MarketWebMapTileUploadService.Result secondResult = MarketWebMapTileUploadService.acceptClientUploadForTest(
                tempDir,
                playerId,
                new MarketWebMapTileUploadPacket("minecraft:overworld", MapLod.LOD_1, 5, -6, 99, png.length, 1, 2, second),
                1_001L);

        assertEquals(MarketWebMapTileUploadService.Result.DISABLED, firstResult);
        assertEquals(MarketWebMapTileUploadService.Result.DISABLED, secondResult);
        assertTrue(new MarketWebMapTileCache(tempDir)
                .readPng("minecraft:overworld", 5, -6)
                .isEmpty());
    }
}
