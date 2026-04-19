package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClaimMapViewportServiceTest {
    @Test
    void serviceReturnsPartialSnapshotUntilVisibleAreaIsComplete() {
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> null);

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 10L, 8, 0, 0, 3),
                List.of()
        );

        assertNotNull(snapshot);
        assertFalse(snapshot.complete());
        assertEquals(0, snapshot.visibleReadyChunkCount());
        assertEquals(289, snapshot.visibleChunkCount());
        assertEquals(0, snapshot.prefetchReadyChunkCount());
        assertEquals(240, snapshot.prefetchChunkCount());
        assertEquals(289, service.pendingVisibleChunkCountForTest());
        assertEquals(240, service.pendingPrefetchChunkCountForTest());
        assertEquals(289 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, snapshot.pixels().size());
    }

    @Test
    void serviceReturnsSnapshotAndAssemblesPixelsWhenVisibleAreaComplete() {
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> new int[]{chunkX * 100 + chunkZ});

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 12L, 1, 10, 20, 0),
                List.of()
        );

        assertNotNull(snapshot);
        assertTrue(snapshot.complete());
        assertEquals("minecraft:overworld", snapshot.dimensionId());
        assertEquals(12L, snapshot.revision());
        assertEquals(9, snapshot.visibleReadyChunkCount());
        assertEquals(9, snapshot.visibleChunkCount());
        assertEquals(0, snapshot.prefetchReadyChunkCount());
        assertEquals(0, snapshot.prefetchChunkCount());
        assertEquals(0, service.pendingVisibleChunkCountForTest());
        assertEquals(0, service.pendingPrefetchChunkCountForTest());
        assertEquals(List.of(
                        919, 919, 919, 919,
                        1019, 1019, 1019, 1019,
                        1119, 1119, 1119, 1119,
                        920, 920, 920, 920,
                        1020, 1020, 1020, 1020,
                        1120, 1120, 1120, 1120,
                        921, 921, 921, 921,
                        1021, 1021, 1021, 1021,
                        1121, 1121, 1121, 1121
                ),
                snapshot.pixels()
        );
    }

    @Test
    void serviceReturnsSnapshotWhenVisibleCompleteEvenIfPrefetchRingHasMissingTiles() {
        Set<Long> missingPrefetchPositions = Set.of(
                key(2, 0),
                key(-2, 1),
                key(0, -2)
        );
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> {
            boolean visible = Math.abs(chunkX) <= 1 && Math.abs(chunkZ) <= 1;
            if (visible) {
                return new int[]{1};
            }
            return missingPrefetchPositions.contains(key(chunkX, chunkZ)) ? null : new int[]{2};
        });

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 13L, 1, 0, 0, 1),
                List.of()
        );

        assertNotNull(snapshot);
        assertTrue(snapshot.complete());
        assertEquals(9, snapshot.visibleReadyChunkCount());
        assertEquals(9, snapshot.visibleChunkCount());
        assertEquals(13, snapshot.prefetchReadyChunkCount());
        assertEquals(16, snapshot.prefetchChunkCount());
        assertEquals(0, service.pendingVisibleChunkCountForTest());
        assertEquals(3, service.pendingPrefetchChunkCountForTest());
        assertEquals(9 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, snapshot.pixels().size());
    }

    @Test
    void viewportMissReturnsPartialSnapshotUntilTileWorkerProducesVisibleTiles() {
        ClaimPreviewTerrainService terrainService = new ClaimPreviewTerrainService();
        ClaimPreviewTerrainService.setActiveForTest(terrainService);
        try {
            ClaimMapViewportService service = new ClaimMapViewportService(terrainService::getTileForTest);
            ClaimMapViewportService.ViewportRequest request = new ClaimMapViewportService.ViewportRequest(
                    "town|owner-a",
                    "minecraft:overworld",
                    21L,
                    1,
                    0,
                    0,
                    1
            );

            ClaimMapViewportSnapshot first = service.tryBuildSnapshot(request, List.of());
            assertNotNull(first);
            assertFalse(first.complete());
            assertEquals(0, first.visibleReadyChunkCount());
            assertEquals(9, first.visibleChunkCount());
            assertEquals(0, first.prefetchReadyChunkCount());
            assertEquals(16, first.prefetchChunkCount());
            assertEquals(9, service.pendingVisibleChunkCountForTest());
            assertEquals(9 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, first.pixels().size());

            terrainService.processBudgetedWorkForTest(9, 0, (dimensionId, chunkX, chunkZ) -> new int[] {chunkX * 100 + chunkZ});

            ClaimMapViewportSnapshot second = service.tryBuildSnapshot(request, List.of());
            assertNotNull(second);
            assertTrue(second.complete());
            assertEquals(9, second.visibleReadyChunkCount());
            assertEquals(9, second.visibleChunkCount());
            assertEquals(0, second.prefetchReadyChunkCount());
            assertEquals(16, second.prefetchChunkCount());
            assertEquals(0, service.pendingVisibleChunkCountForTest());
            assertEquals(9 * ClaimPreviewTerrainService.SUB * ClaimPreviewTerrainService.SUB, second.pixels().size());
        } finally {
            ClaimPreviewTerrainService.clearActiveForTest();
        }
    }

    @Test
    void snapshotNormalizesNullDimensionAndPixels() {
        ClaimMapViewportSnapshot snapshot = new ClaimMapViewportSnapshot(null, 7L, 6, 12, 24, null, false, -2, -5, -1, -7);

        assertEquals("", snapshot.dimensionId());
        assertEquals(List.of(), snapshot.pixels());
        assertFalse(snapshot.complete());
        assertEquals(0, snapshot.visibleReadyChunkCount());
        assertEquals(0, snapshot.visibleChunkCount());
        assertEquals(0, snapshot.prefetchReadyChunkCount());
        assertEquals(0, snapshot.prefetchChunkCount());
    }

    @Test
    void latestRequestReplacesOlderRequestForSameScreenKey() {
        ClaimMapTaskService service = new ClaimMapTaskService(Runnable::run, Runnable::run);
        AtomicInteger appliedRevision = new AtomicInteger();

        ClaimMapTaskService.TaskHandle<Integer> first = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-viewport", "player-a|town-a"),
                () -> 1,
                appliedRevision::set
        );
        ClaimMapTaskService.TaskHandle<Integer> second = service.submitForTest(
                new ClaimMapTaskService.TaskKey("town-viewport", "player-a|town-a"),
                () -> 2,
                appliedRevision::set
        );

        first.completeForTest();
        second.completeForTest();

        assertEquals(2, appliedRevision.get());
    }

    private static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }
}
