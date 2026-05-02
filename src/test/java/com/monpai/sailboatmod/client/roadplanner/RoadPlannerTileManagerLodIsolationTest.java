package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
