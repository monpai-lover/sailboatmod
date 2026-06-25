package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class MarketWebMapRenderManagerTest {
    // 部分刷新阈值现按 config 值生效(异步离线读模式下,死等满 1024 会让含 PENDING chunk 的 region
    // 永不写图 → 底图全黑)。两类 SERVER_* quality 用同一阈值;writeDirty 的 mergeTouchedPixels 增量合并
    // 保证渐进显示不画脏条纹,故不再需要"等满整个 region"。
    @Test
    void partialRegionFlushUsesConfiguredThresholdForRegionScan() {
        assertEquals(
                32,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        32));
    }

    @Test
    void partialRegionFlushUsesConfiguredThresholdForLoadedChunks() {
        assertEquals(
                32,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_LOADED_CHUNK,
                        32));
    }

    @Test
    void partialRegionFlushFallsBackWhenConfiguredValueNonPositive() {
        assertEquals(
                64,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        0));
    }

    @Test
    void partialRegionFlushClampsToFullRegion() {
        assertEquals(
                MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS * MarketWebMapRegionImage.CHUNKS_PER_REGION_AXIS,
                MarketWebMapRenderManager.effectivePartialRegionFlushChunks(
                        MarketWebMapTileQuality.SERVER_REGION_SCAN,
                        99999));
    }

    @Test
    void areaRenderConvertsBlockBoundsToInclusiveChunkBounds() {
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapRenderManager.areaChunksForTest(15, -17, 32, 0);

        assertIterableEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, -2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, -1),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 0),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, 0)
        ), chunks);
    }

    @Test
    void areaRenderNormalizesReversedBlockBounds() {
        List<MarketWebMapDirtyChunkQueue.ChunkCoordinate> chunks =
                MarketWebMapRenderManager.areaChunksForTest(32, 0, 15, -17);

        assertEquals(9, chunks.size());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 0, -2), chunks.get(0));
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 2, 0), chunks.get(chunks.size() - 1));
    }
}
