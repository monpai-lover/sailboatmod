package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    void chunkSamplingUsesForceLoadWhenResolvingChunk() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        AtomicBoolean forceLoad = new AtomicBoolean(false);

        int[] sampled = service.sampleChunkSubColorsForTest(4, 9, (chunkX, chunkZ, load) -> {
            forceLoad.set(load);
            return null;
        });

        assertTrue(forceLoad.get());
        assertNull(sampled);
    }

    @Test
    void processBudgetedWorkForTestUsesParallelVisibleSamplingBatches() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();
        service.enqueueViewportForTest("minecraft:overworld", 0, 0, 1, 0, 19L, "town|parallel");
        AtomicInteger started = new AtomicInteger();
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread releaser = new Thread(() -> {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200);
            while (started.get() < 2 && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            release.countDown();
        });
        releaser.start();

        service.processBudgetedWorkForTest(4, 0, (dimensionId, chunkX, chunkZ) -> {
            started.incrementAndGet();
            int concurrent = running.incrementAndGet();
            maxRunning.accumulateAndGet(concurrent, Math::max);
            try {
                assertTrue(release.await(1, java.util.concurrent.TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                running.decrementAndGet();
            }
            return new int[] {chunkX, chunkZ, 1, 2};
        });

        try {
            releaser.join(1000L);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        assertTrue(maxRunning.get() >= 2);
    }

    @Test
    void invalidationQueuesChangedChunkResampleWithoutDependentViewport() {
        ClaimPreviewTerrainService service = new ClaimPreviewTerrainService();

        service.invalidateChunkForTest("minecraft:overworld", 12, -8);

        assertEquals(1, service.visibleQueueSizeForTest());
    }
}
