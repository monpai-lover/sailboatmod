package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRegionScanServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void scansRegionFilenamesNearestFirstAndIgnoresInvalidFiles() throws Exception {
        Files.createFile(tempDir.resolve("r.4.0.mca"));
        Files.createFile(tempDir.resolve("r.1.0.mca"));
        Files.createFile(tempDir.resolve("r.-1.-1.mca"));
        Files.createFile(tempDir.resolve("r.bad.0.mca"));
        Files.createFile(tempDir.resolve("notes.txt"));

        List<MarketWebMapRegionScanService.RegionFile> regions =
                MarketWebMapRegionScanService.scanRegionFilesForTest(tempDir, 0, 0, 3);

        assertEquals(3, regions.size());
        assertEquals(new MarketWebMapRegionScanService.RegionFile(1, 0), regions.get(0));
        assertEquals(new MarketWebMapRegionScanService.RegionFile(-1, -1), regions.get(1));
        assertEquals(new MarketWebMapRegionScanService.RegionFile(4, 0), regions.get(2));
    }

    @Test
    void regionScanQueuesOnlyLoadedChunksWithoutForcingLoads() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(64);
        Set<String> loaded = Set.of("32:64", "63:95");

        int queued = MarketWebMapRegionScanService.enqueueLoadedRegionChunksForTest(
                queue,
                "minecraft:overworld",
                1,
                2,
                10L,
                (chunkX, chunkZ) -> loaded.contains(chunkX + ":" + chunkZ));

        assertEquals(2, queued);
        List<MarketWebMapRenderQueue.Task> tasks = queue.poll(8, 11L);
        assertTrue(tasks.stream().anyMatch(task -> task.chunkX() == 32 && task.chunkZ() == 64));
        assertTrue(tasks.stream().anyMatch(task -> task.chunkX() == 63 && task.chunkZ() == 95));
    }

}
