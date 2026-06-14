package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebMapSpiralChunkIteratorTest {
    @Test
    void radiusZeroStartsAtCenterChunk() {
        assertEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 10, -4)
        ), MarketWebMapSpiralChunkIterator.squareSpiral(MarketWebMapConstants.OVERWORLD, 10, -4, 0));
    }

    @Test
    void radiusOneWalksCenterOutwardWithoutRegionOverdraw() {
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapSpiralChunkIterator.squareSpiral(MarketWebMapConstants.OVERWORLD, 0, 0, 1);

        assertEquals(9, chunks.size());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, 0), chunks.get(0));
        assertEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, 1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -1, 1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -1, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -1, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, -1)
        ), chunks);
    }
}
