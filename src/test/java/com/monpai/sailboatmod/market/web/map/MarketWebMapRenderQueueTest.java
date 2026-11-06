package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRenderQueueTest {
    @Test
    void productionOverviewCursorStopsAfter256LoadedLookupsWithoutEnqueuingUnloadedTerrain() {
        MarketWebMapRenderService.TileCursor cursor = new MarketWebMapRenderService.TileCursor(
                "minecraft:overworld", 512, 0, 0);
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);
        AtomicInteger lookups = new AtomicInteger();
        while (!cursor.done()) {
            cursor.enqueue(queue, 1L, 128, (x, z) -> { lookups.incrementAndGet(); return false; }, () -> true);
        }
        assertEquals(256, lookups.get(), "an overview must never expand all 262144 candidates");
        assertEquals(0, queue.size(), "browser misses may only discover loaded chunks");
    }

    @Test
    void browserCursorRetainsItsPositionWhilePausedAndStopsAt64QueuedChunks() {
        MarketWebMapRenderService.TileCursor cursor = new MarketWebMapRenderService.TileCursor(
                "minecraft:overworld", 512, 0, 0);
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);
        assertEquals(0, cursor.enqueue(queue, 1L, 128, (x, z) -> true, () -> false));
        assertFalse(cursor.done());
        assertEquals(64, cursor.enqueue(queue, 2L, 128, (x, z) -> true, () -> true));
        assertTrue(cursor.done());
        assertEquals(64, queue.size());
    }

    @Test
    void pausedPendingTasksSurviveElapsedWallClockAndCapacityPressure() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(2);
        assertTrue(queue.enqueue("minecraft:overworld", 1, 0, 0L));
        assertTrue(queue.enqueue("minecraft:overworld", 2, 0, 1L));
        assertFalse(queue.enqueue("minecraft:overworld", 3, 0, 999_999L),
                "bounded discovery must not evict already accepted paused work");
        assertEquals(List.of(1, 2), queue.poll(2, 999_999L).stream().map(MarketWebMapRenderQueue.Task::chunkX).toList());
    }

    @Test
    void queueDeduplicatesChunkTasksAndRespectsBudget() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(16);

        assertTrue(queue.enqueue("minecraft:overworld", 1, 2, 1L));
        assertFalse(queue.enqueue("minecraft:overworld", 1, 2, 2L));
        assertTrue(queue.enqueue("minecraft:overworld", 2, 2, 3L));

        List<MarketWebMapRenderQueue.Task> first = queue.poll(1, 4L);
        assertEquals(1, first.size());
        assertEquals(1, first.get(0).chunkX());
        assertEquals(1, queue.size());

        List<MarketWebMapRenderQueue.Task> second = queue.poll(4, 5L);
        assertEquals(1, second.size());
        assertEquals(2, second.get(0).chunkX());
        assertEquals(0, queue.size());
    }

    @Test
    void tileRequestQueuesOnlyLoadedChunks() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(512);
        Set<String> loaded = Set.of("32:48", "33:48", "47:63");

        int queued = MarketWebMapRenderService.enqueueTileChunksForTest(
                queue,
                "minecraft:overworld",
                2,
                3,
                10L,
                (chunkX, chunkZ) -> loaded.contains(chunkX + ":" + chunkZ));

        assertEquals(3, queued);
        List<MarketWebMapRenderQueue.Task> tasks = queue.poll(16, 11L);
        assertEquals(3, tasks.size());
        assertTrue(tasks.stream().anyMatch(task -> task.chunkX() == 32 && task.chunkZ() == 48));
        assertTrue(tasks.stream().anyMatch(task -> task.chunkX() == 33 && task.chunkZ() == 48));
        assertTrue(tasks.stream().anyMatch(task -> task.chunkX() == 47 && task.chunkZ() == 63));
    }

    @Test
    void queuePollsMoreTasksWhenTheyBelongToSameBaseTile() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(512);
        for (int i = 0; i < 16; i++) {
            assertTrue(queue.enqueue("minecraft:overworld", i, 0, i));
        }

        List<MarketWebMapRenderQueue.Task> tasks = queue.pollCoalesced(4, 16, 20L);

        assertEquals(16, tasks.size(), "same tile work should batch together for faster PNG writes");
    }

    @Test
    void higherQualityRegionScanReplacesQueuedLoadedChunkTask() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(16);

        assertTrue(queue.enqueue("minecraft:overworld", 4, 5, 1L, MarketWebMapTileQuality.SERVER_LOADED_CHUNK));
        assertTrue(queue.enqueue("minecraft:overworld", 4, 5, 2L, MarketWebMapTileQuality.SERVER_REGION_SCAN));

        List<MarketWebMapRenderQueue.Task> tasks = queue.poll(4, 3L);

        assertEquals(1, tasks.size());
        assertEquals(MarketWebMapTileQuality.SERVER_REGION_SCAN, tasks.get(0).quality());
    }

    @Test
    void squareTileMissQueuesOnlyPredicateVisibleWork() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);

        int queued = MarketWebMapRenderService.enqueueSquareTileChunksForTest(
                queue,
                "minecraft:overworld",
                0,
                0,
                0,
                10L,
                (chunkX, chunkZ) -> chunkX >= 0 && chunkX < 4 && chunkZ >= 0 && chunkZ < 4);

        assertEquals(16, queued);
        assertEquals(16, queue.poll(64, 11L).size());
    }

    @Test
    void squareTileMissDoesNotQueueRegionScanWorkWithoutGeneratedRegionEvidence() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);

        int queued = MarketWebMapRenderService.enqueueSquareTileRegionScanForTest(
                queue,
                "minecraft:overworld",
                0,
                0,
                0,
                10L,
                48);

        assertEquals(0, queued);
        assertEquals(0, queue.poll(64, 11L).size());
    }
}
