package com.monpai.sailboatmod.nation.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(service.pendingPrefetchChunkCountForTest() > 289);
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
}
