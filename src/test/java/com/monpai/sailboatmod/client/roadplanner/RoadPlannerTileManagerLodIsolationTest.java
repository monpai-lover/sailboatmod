package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTileManagerLodIsolationTest {
    @TempDir
    Path tempDir;

    @Test
    void differentLodsUseDifferentCacheBuckets() {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");

        RoadPlannerTile lod1 = manager.getOrCreateTile(0, 0, MapLod.LOD_1);
        RoadPlannerTile lod4 = manager.getOrCreateTile(0, 0, MapLod.LOD_4);
        manager.saveTile(lod1);
        manager.saveTile(lod4);

        assertNotSame(lod1, lod4);
        assertEquals(2, manager.loadedTileCount());
        assertTrue(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_1").resolve("0_0.png").toFile().exists());
        assertTrue(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_4").resolve("0_0.png").toFile().exists());
    }

    @Test
    void tileSyncPopulatesLod1CacheForRouteRenderedUnknownTile() {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] pixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(pixels, 0xFF336699);
        RoadPlannerMapTileSyncPacket packet = new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                pixels);

        int applied = manager.applyTileSync(packet);

        assertEquals(1, applied);
        assertEquals(1, manager.loadedTileCount());
        assertTrue(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_1").resolve("2_-3.png").toFile().exists());
        assertFalse(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_4").resolve("2_-3.png").toFile().exists());
    }

    @Test
    void forceRenderTileSyncMergesCoveredPixelsWithoutErasingKnownMap() throws IOException {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] knownPixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(knownPixels, 0xFF00AA00);
        manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                knownPixels));

        int[] forcePixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(forcePixels, 0xFF252525);
        boolean[] coverageMask = new boolean[forcePixels.length];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int index = y * RoadPlannerTile.TILE_PIXEL_SIZE + x;
                coverageMask[index] = true;
                forcePixels[index] = 0xFF0000CC;
            }
        }

        manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                43L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                forcePixels,
                coverageMask));

        var file = rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_1").resolve("2_-3.png").toFile();
        try (var image = com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.readAllBytes(file.toPath()))) {
            assertEquals(0xFF0000CC, image.getPixelRGBA(0, 0));
            assertEquals(0xFF00AA00, image.getPixelRGBA(32, 32));
        }
    }

    @Test
    void mergedTileSnapshotKeepsKnownPixelsAfterPartialSync() {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] knownPixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(knownPixels, 0xFF00AA00);
        manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                knownPixels));

        int[] forcePixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(forcePixels, 0xFF000000);
        boolean[] coverageMask = new boolean[forcePixels.length];
        coverageMask[0] = true;
        forcePixels[0] = 0xFF0000CC;
        RoadPlannerMapTileSyncPacket partialPacket = new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                43L,
                RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                forcePixels,
                coverageMask);
        manager.applyTileSync(partialPacket);

        int[] snapshot = manager.copyTilePixels(partialPacket);

        assertEquals(0xFF0000CC, snapshot[0]);
        assertEquals(0xFF00AA00, snapshot[32 * RoadPlannerTile.TILE_PIXEL_SIZE + 32]);
    }

    @Test
    void builtRoadRefreshOverwritesOldBlackLodCache() throws IOException {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] blackPixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(blackPixels, 0xFF000000);
        manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                42L,
                RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_4,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                blackPixels));

        int[] refreshedPixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(refreshedPixels, 0xFF556677);
        manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                43L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_4,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                refreshedPixels));

        var file = rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_4").resolve("2_-3.png").toFile();
        try (var image = com.mojang.blaze3d.platform.NativeImage.read(java.nio.file.Files.readAllBytes(file.toPath()))) {
            assertEquals(0xFF556677, image.getPixelRGBA(0, 0));
            assertEquals(0xFF556677, image.getPixelRGBA(128, 128));
        }
    }

    @Test
    void builtRoadRefreshDoesNotCreatePartialPlaceholderTileWhenBaseCacheIsMissing() {
        File rootDir = tempDir.toFile();
        RoadPlannerTileManager manager = RoadPlannerTileManager.forTest(rootDir, "world_a", "minecraft:overworld");
        int[] refreshedPixels = new int[RoadPlannerTile.TILE_PIXEL_SIZE * RoadPlannerTile.TILE_PIXEL_SIZE];
        Arrays.fill(refreshedPixels, 0xFF000000);
        boolean[] coverageMask = new boolean[refreshedPixels.length];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                coverageMask[y * RoadPlannerTile.TILE_PIXEL_SIZE + x] = true;
                refreshedPixels[y * RoadPlannerTile.TILE_PIXEL_SIZE + x] = 0xFF556677;
            }
        }

        int applied = manager.applyTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                43L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_4,
                2,
                -3,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                RoadPlannerTile.TILE_PIXEL_SIZE,
                refreshedPixels,
                coverageMask));

        assertEquals(0, applied);
        assertEquals(0, manager.loadedTileCount());
        assertFalse(rootDir.toPath().resolve("world_a").resolve("minecraft_overworld").resolve("lod_4").resolve("2_-3.png").toFile().exists());
    }
}
