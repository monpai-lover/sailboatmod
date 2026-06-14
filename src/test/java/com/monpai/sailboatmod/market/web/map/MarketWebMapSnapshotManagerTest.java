package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSample;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class MarketWebMapSnapshotManagerTest {
    @Test
    void deduplicatesSnapshotRequestsForTheSameChunk() {
        FakeSnapshotProvider provider = new FakeSnapshotProvider();
        MarketWebMapSnapshotManager manager = new MarketWebMapSnapshotManager(provider, 8, 4, false);

        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> first =
                manager.snapshotDirect(MarketWebMapConstants.OVERWORLD, 7, -2, MarketWebMapTileQuality.SERVER_REGION_SCAN);
        CompletableFuture<Optional<MarketWebMapChunkSnapshot>> second =
                manager.snapshotDirect(MarketWebMapConstants.OVERWORLD, 7, -2, MarketWebMapTileQuality.SERVER_REGION_SCAN);

        assertSame(first, second);
        assertEquals(List.of(ChunkPos.asLong(7, -2)), provider.requests);
    }

    @Test
    void limitsActiveSnapshotReadsAndStartsQueuedReadsWhenOneCompletes() {
        FakeSnapshotProvider provider = new FakeSnapshotProvider();
        MarketWebMapSnapshotManager manager = new MarketWebMapSnapshotManager(provider, 8, 1, false);

        manager.snapshotDirect(MarketWebMapConstants.OVERWORLD, 0, 0, MarketWebMapTileQuality.SERVER_REGION_SCAN);
        manager.snapshotDirect(MarketWebMapConstants.OVERWORLD, 1, 0, MarketWebMapTileQuality.SERVER_REGION_SCAN);

        assertEquals(List.of(ChunkPos.asLong(0, 0)), provider.requests);

        provider.complete(0, 0);

        assertEquals(List.of(ChunkPos.asLong(0, 0), ChunkPos.asLong(1, 0)), provider.requests);
    }

    @Test
    void verticalNeighborPrefetchPrimesNorthAndSouthChunksForSeamlessHeightShading() {
        FakeSnapshotProvider provider = new FakeSnapshotProvider();
        MarketWebMapSnapshotManager manager = new MarketWebMapSnapshotManager(provider, 8, 8, true);

        manager.snapshotWithVerticalNeighbors(MarketWebMapConstants.OVERWORLD, 4, 8, MarketWebMapTileQuality.SERVER_REGION_SCAN);

        assertEquals(List.of(
                ChunkPos.asLong(4, 7),
                ChunkPos.asLong(4, 8),
                ChunkPos.asLong(4, 9)
        ), provider.requests);
    }

    private static final class FakeSnapshotProvider implements MarketWebMapSnapshotManager.SnapshotProvider {
        private final List<Long> requests = new ArrayList<>();
        private final Map<Long, CompletableFuture<Optional<MarketWebMapChunkSnapshot>>> futures = new HashMap<>();

        @Override
        public CompletableFuture<Optional<MarketWebMapChunkSnapshot>> readSnapshot(String dimensionId,
                                                                                   int chunkX,
                                                                                   int chunkZ,
                                                                                   MarketWebMapTileQuality quality) {
            long key = ChunkPos.asLong(chunkX, chunkZ);
            requests.add(key);
            return futures.computeIfAbsent(key, ignored -> new CompletableFuture<>());
        }

        private void complete(int chunkX, int chunkZ) {
            futures.get(ChunkPos.asLong(chunkX, chunkZ)).complete(Optional.of(snapshot(chunkX, chunkZ)));
        }
    }

    private static MarketWebMapChunkSnapshot snapshot(int chunkX, int chunkZ) {
        RoadMapColumnSample[] samples = new RoadMapColumnSample[MarketWebMapConstants.CHUNK_SIZE * MarketWebMapConstants.CHUNK_SIZE];
        for (int z = 0; z < MarketWebMapConstants.CHUNK_SIZE; z++) {
            for (int x = 0; x < MarketWebMapConstants.CHUNK_SIZE; x++) {
                samples[z * MarketWebMapConstants.CHUNK_SIZE + x] = new RoadMapColumnSample(
                        chunkX * MarketWebMapConstants.CHUNK_SIZE + x,
                        64,
                        chunkZ * MarketWebMapConstants.CHUNK_SIZE + z,
                        0xFF55AA55,
                        false,
                        0,
                        64);
            }
        }
        return new MarketWebMapChunkSnapshot(MarketWebMapConstants.OVERWORLD, chunkX, chunkZ, samples);
    }
}
