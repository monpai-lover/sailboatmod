package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapDirtyRegionQueueTest {
    @TempDir
    Path tempDir;

    @Test
    void deduplicatesRegionsAndExpandsChunksByBudget() {
        MarketWebMapDirtyRegionQueue queue = new MarketWebMapDirtyRegionQueue();

        assertTrue(queue.markDirty(MarketWebMapConstants.OVERWORLD, 2, -1));
        assertEquals(false, queue.markDirty(MarketWebMapConstants.OVERWORLD, 2, -1));

        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> first = queue.expandChunks(3);

        assertEquals(3, first.size());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 64, -32), first.get(0));
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 65, -32), first.get(1));
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 66, -32), first.get(2));
        assertEquals(1, queue.size());
        assertEquals(3, queue.inProgressLocalChunk());

        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> rest = queue.expandChunks(1024);

        assertEquals(1021, rest.size());
        assertEquals(0, queue.size());
        assertEquals(0, queue.inProgressLocalChunk());
    }

    @Test
    void savesAndLoadsDirtyRegionProgress() {
        Path file = tempDir.resolve("dirty_regions.json");
        MarketWebMapDirtyRegionQueue queue = new MarketWebMapDirtyRegionQueue();
        queue.markDirty(MarketWebMapConstants.OVERWORLD, -3, 4);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        assertEquals(17, queue.expandChunks(17).size());

        assertTrue(queue.save(file));
        assertTrue(Files.isRegularFile(file));

        MarketWebMapDirtyRegionQueue loaded = new MarketWebMapDirtyRegionQueue();
        assertEquals(2, loaded.load(file));
        assertEquals(2, loaded.size());
        assertEquals(17, loaded.inProgressLocalChunk());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -96 + 17, 128),
                loaded.expandChunks(1).get(0));
    }
}
