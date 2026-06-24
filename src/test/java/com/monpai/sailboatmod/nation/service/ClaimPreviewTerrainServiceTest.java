package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimPreviewTerrainServiceTest {
    @Test
    void visibleChunkWorkIsScheduledAheadOfPrefetchWork() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();

        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 3, 5L, "town|a");

        assertEquals(9, service.visibleQueueSizeForTest());
        assertTrue(service.prefetchQueueSizeForTest() > 9);
    }

    @Test
    void processBudgetedWorkDrainsVisibleTilesBeforePrefetchTiles() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        ClaimPreviewTerrainService.setActiveForTest(service);
        try {
            service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 1, 7L, "town|owner-a");

            service.processBudgetedWorkForTest(1, 1, (dimensionId, chunkX, chunkZ) -> new int[] {chunkX, chunkZ, 99, 100});

            assertEquals(8, service.visibleQueueSizeForTest());
            assertEquals(16, service.prefetchQueueSizeForTest());
            assertTrue(service.getTileForTest("minecraft:overworld", -1, -1) != null);
            assertNull(service.getTileForTest("minecraft:overworld", -2, -2));

            service.processBudgetedWorkForTest(8, 1, (dimensionId, chunkX, chunkZ) -> new int[] {chunkX, chunkZ, 99, 100});

            assertEquals(0, service.visibleQueueSizeForTest());
            assertEquals(15, service.prefetchQueueSizeForTest());
            assertTrue(service.getTileForTest("minecraft:overworld", -2, -2) != null);
        } finally {
            ClaimPreviewTerrainService.clearActiveForTest();
        }
    }

    @Test
    void invalidatingChunkClearsTileAndDependentSnapshots() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        service.putTileForTest("minecraft:overworld", 2, 3, new int[] {1, 2, 3, 4});
        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 0, 11L, "player-a|TOWN|owner-a");
        service.clearQueuedWorkForTest();
        service.putViewportDependencyForTest("minecraft:overworld", 2, 3, "player-a|TOWN|owner-a");

        service.invalidateChunkForTest("minecraft:overworld", 2, 3);

        assertNull(service.getTileForTest("minecraft:overworld", 2, 3));
        assertTrue(service.invalidatedViewportKeysForTest().contains("player-a|TOWN|owner-a"));
        assertEquals(10, service.visibleQueueSizeForTest());
    }

    @Test
    void invalidationRequeuesLatestViewportRequestForLogicalScreenKey() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        String screenKey = UUID.randomUUID() + "|TOWN|owner-a";
        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 0, 11L, screenKey);
        service.clearQueuedWorkForTest();
        service.enqueueViewportForTest("minecraft:overworld", 8, 8, 0, 0, 12L, screenKey);
        service.clearQueuedWorkForTest();
        service.putViewportDependencyForTest("minecraft:overworld", 2, 3, screenKey);

        service.invalidateChunkForTest("minecraft:overworld", 2, 3);

        assertTrue(service.invalidatedViewportKeysForTest().contains(screenKey));
        assertEquals(2, service.visibleQueueSizeForTest());
    }

    @Test
    void unregisterViewportCleansStateAndPreventsClosedScreenRequeue() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        String screenKey = UUID.randomUUID() + "|TOWN|owner-a";
        ClaimPreviewTerrainService.setActiveForTest(service);
        try {
            service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 0, 13L, screenKey);
            service.clearQueuedWorkForTest();
            service.putViewportDependencyForTest("minecraft:overworld", 2, 3, screenKey);

            assertTrue(service.hasViewportRequestForTest(screenKey));
            assertTrue(service.hasViewportDependencyForTest("minecraft:overworld", 2, 3, screenKey));

            ClaimPreviewTerrainService.unregisterViewport(screenKey);

            assertFalse(service.hasViewportRequestForTest(screenKey));
            assertFalse(service.hasViewportDependencyForTest("minecraft:overworld", 2, 3, screenKey));

            service.invalidateChunkForTest("minecraft:overworld", 2, 3);

            assertTrue(service.invalidatedViewportKeysForTest().isEmpty());
            assertEquals(1, service.visibleQueueSizeForTest());
        } finally {
            ClaimPreviewTerrainService.clearActiveForTest();
        }
    }

    @Test
    void chunkSamplingDoesNotForceLoadWhenResolvingChunk() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        AtomicBoolean forceLoad = new AtomicBoolean(false);

        int[] sampled = service.sampleChunkSubColorsForTest(4, 9, (chunkX, chunkZ, load) -> {
            forceLoad.set(load);
            return null;
        });

        assertFalse(forceLoad.get());
        assertNull(sampled);
    }

    @Test
    void productionSamplingDoesNotUseBlockingChunkWork() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/nation/service/ClaimPreviewTerrainService.java"),
                StandardCharsets.UTF_8);

        // 改用后台 IOWorker 异步读盘 + 后台自解颜色:主线程只发起异步读、派后台解码,绝不 force/阻塞。
        // 允许 CompletableFuture(后台异步读 future);禁的是「主线程同步阻塞」与「force 生成区块」。
        assertFalse(source.contains("ChunkStatus.FULL"),
                "Claim preview must not request FULL chunks (sync generation) during server tick");
        assertFalse(source.contains("ForgeChunkManager"),
                "Claim preview must not force-load/generate chunks (use OfflineChunkNbtReader async read instead)");
        assertFalse(source.contains("forceChunk"),
                "Claim preview must not force chunks (force ungenerated chunk = sync worldgen = watchdog hang)");
        assertFalse(source.contains(".join()"),
                "Claim preview must not block server tick with .join() on worker futures");
    }

    @Test
    void invalidationQueuesChangedChunkResampleWithoutDependentViewport() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();

        service.invalidateChunkForTest("minecraft:overworld", 12, -8);

        assertEquals(1, service.visibleQueueSizeForTest());
    }

    @Test
    void visibleBudgetCapsSampledTilesPerTick() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        java.util.concurrent.atomic.AtomicInteger sampled = new java.util.concurrent.atomic.AtomicInteger();
        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 3, 0, 22L, "town|budget");

        service.processBudgetedWorkForTest(5, 0, (dimensionId, chunkX, chunkZ) -> {
            sampled.incrementAndGet();
            return new int[] {1, 2, 3, 4};
        });

        assertEquals(5, sampled.get());
        assertEquals(44, service.visibleQueueSizeForTest());
    }

    @Test
    void prefetchBudgetIsNotSpentUntilVisibleQueueIsEmpty() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        java.util.concurrent.atomic.AtomicInteger sampled = new java.util.concurrent.atomic.AtomicInteger();
        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 1, 23L, "town|prefetch");

        service.processBudgetedWorkForTest(1, 99, (dimensionId, chunkX, chunkZ) -> {
            sampled.incrementAndGet();
            return new int[] {1, 2, 3, 4};
        });

        assertEquals(1, sampled.get());
        assertEquals(8, service.visibleQueueSizeForTest());
        assertEquals(16, service.prefetchQueueSizeForTest());
    }
}
