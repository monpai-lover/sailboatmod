package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapRenderQueueTest {
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
    void squareTileMissQueuesBoundedRegionScanWorkWithoutLoadedPredicate() {
        MarketWebMapRenderQueue queue = new MarketWebMapRenderQueue(4096);

        int queued = MarketWebMapRenderService.enqueueSquareTileRegionScanForTest(
                queue,
                "minecraft:overworld",
                0,
                0,
                0,
                10L,
                48);

        assertEquals(48, queued);
        List<MarketWebMapRenderQueue.Task> tasks = queue.poll(64, 11L);
        assertEquals(48, tasks.size());
        assertEquals(MarketWebMapTileQuality.SERVER_REGION_SCAN, tasks.get(0).quality());
        assertEquals(0, tasks.get(0).chunkX());
        assertEquals(0, tasks.get(0).chunkZ());
        assertTrue(tasks.stream().allMatch(task -> task.quality() == MarketWebMapTileQuality.SERVER_REGION_SCAN));
    }
}
