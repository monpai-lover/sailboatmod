package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.core.BlockPos;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRegionScanServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void scansRegionFilenamesNearestFirstAndIgnoresInvalidFiles() throws Exception {
        writeRegionFile(tempDir.resolve("r.4.0.mca"), 0);
        writeRegionFile(tempDir.resolve("r.1.0.mca"), 0, 1, 35);
        writeRegionFile(tempDir.resolve("r.-1.-1.mca"), 7);
        Files.createFile(tempDir.resolve("r.bad.0.mca"));
        Files.createFile(tempDir.resolve("notes.txt"));

        List<MarketWebMapRegionScanService.RegionFile> regions =
                MarketWebMapRegionScanService.scanRegionFilesForTest(tempDir, 0, 0, 3);

        assertEquals(3, regions.size());
        assertEquals(1, regions.get(0).regionX());
        assertEquals(0, regions.get(0).regionZ());
        assertEquals(List.of(0, 1, 35), regions.get(0).localChunks());
        assertEquals(-1, regions.get(1).regionX());
        assertEquals(-1, regions.get(1).regionZ());
        assertEquals(4, regions.get(2).regionX());
        assertEquals(0, regions.get(2).regionZ());
    }

    @Test
    void skipsZeroLengthAndHeaderOnlyRegionFiles() throws Exception {
        Files.createFile(tempDir.resolve("r.0.0.mca"));
        writeRegionFile(tempDir.resolve("r.1.0.mca"));
        writeRegionFile(tempDir.resolve("r.2.0.mca"), 12);

        List<MarketWebMapRegionScanService.RegionFile> regions =
                MarketWebMapRegionScanService.scanRegionFilesForTest(tempDir, 0, 0, 10);

        assertEquals(1, regions.size());
        assertEquals(2, regions.get(0).regionX());
        assertEquals(List.of(12), regions.get(0).localChunks());
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

    @Test
    void renderScanCenterPrefersOnlinePlayerPositionOverSpawn() {
        MarketWebMapRegionScanService.RegionCenter center =
                MarketWebMapRegionScanService.centerRegionForTest(
                        List.of(new BlockPos(2048, 80, -1024)),
                        new BlockPos(0, 80, 0));

        assertEquals(4, center.regionX());
        assertEquals(-2, center.regionZ());
    }

    private static void writeRegionFile(Path path, int... localChunks) throws Exception {
        byte[] bytes = new byte[8192 + Math.max(1, localChunks.length) * 4096];
        Arrays.fill(bytes, (byte) 0);
        int sector = 2;
        for (int localChunk : localChunks) {
            int offset = localChunk * 4;
            bytes[offset] = (byte) ((sector >>> 16) & 0xFF);
            bytes[offset + 1] = (byte) ((sector >>> 8) & 0xFF);
            bytes[offset + 2] = (byte) (sector & 0xFF);
            bytes[offset + 3] = 1;
            sector++;
        }
        Files.write(path, bytes);
    }
}
