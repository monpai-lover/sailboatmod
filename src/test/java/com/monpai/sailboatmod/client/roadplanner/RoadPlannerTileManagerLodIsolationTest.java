package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
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
}
