package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClaimMapViewportServiceTest {
    @Test
    void serviceDoesNotReturnSnapshotUntilVisibleAreaIsComplete() {
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> null);

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 10L, 8, 0, 0, 3),
                List.of()
        );

        assertNull(snapshot);
        assertEquals(289, service.pendingVisibleChunkCountForTest());
        assertEquals(240, service.pendingPrefetchChunkCountForTest());
    }

    @Test
    void serviceReturnsSnapshotAndAssemblesPixelsWhenVisibleAreaComplete() {
        ClaimMapViewportService service = new ClaimMapViewportService((dimension, chunkX, chunkZ) -> new int[]{chunkX * 100 + chunkZ});

        ClaimMapViewportSnapshot snapshot = service.tryBuildSnapshot(
                new ClaimMapViewportService.ViewportRequest("town", "minecraft:overworld", 12L, 1, 10, 20, 0),
                List.of()
        );

        assertNotNull(snapshot);
        assertEquals("minecraft:overworld", snapshot.dimensionId());
        assertEquals(12L, snapshot.revision());
        assertEquals(0, service.pendingVisibleChunkCountForTest());
        assertEquals(0, service.pendingPrefetchChunkCountForTest());
        assertEquals(List.of(
                        919, 1019, 1119,
                        920, 1020, 1120,
                        921, 1021, 1121
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
        assertEquals(0, service.pendingVisibleChunkCountForTest());
        assertEquals(3, service.pendingPrefetchChunkCountForTest());
        assertEquals(9, snapshot.pixels().size());
    }

    @Test
    void snapshotNormalizesNullDimensionAndPixels() {
        ClaimMapViewportSnapshot snapshot = new ClaimMapViewportSnapshot(null, 7L, 6, 12, 24, null);

        assertEquals("", snapshot.dimensionId());
        assertEquals(List.of(), snapshot.pixels());
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
