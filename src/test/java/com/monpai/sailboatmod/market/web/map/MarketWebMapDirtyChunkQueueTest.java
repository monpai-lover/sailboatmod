package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapDirtyChunkQueueTest {
    @TempDir
    Path tempDir;

    @Test
    void deduplicatesPollsAndRequeuesFailedChunks() {
        MarketWebMapDirtyChunkQueue queue = new MarketWebMapDirtyChunkQueue();

        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 3, 4);

        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> first = queue.poll(1);

        assertEquals(1, first.size());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 2), first.get(0));
        assertEquals(1, queue.size());

        queue.requeue(first);

        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> remaining = queue.poll(4);
        assertEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 3, 4),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 2)
        ), remaining);
    }

    @Test
    void savesAndLoadsDirtyChunksForRestartRecovery() {
        Path file = tempDir.resolve("dirty_chunks.json");
        MarketWebMapDirtyChunkQueue queue = new MarketWebMapDirtyChunkQueue();
        queue.markDirty(MarketWebMapConstants.OVERWORLD, -2, 5);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 8, -9);

        assertTrue(queue.save(file));
        assertTrue(Files.isRegularFile(file));

        MarketWebMapDirtyChunkQueue loaded = new MarketWebMapDirtyChunkQueue();
        assertEquals(2, loaded.load(file));

        assertEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -2, 5),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 8, -9)
        ), loaded.poll(8));
    }
}
