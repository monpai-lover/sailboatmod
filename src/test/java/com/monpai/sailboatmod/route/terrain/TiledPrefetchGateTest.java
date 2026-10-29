package com.monpai.sailboatmod.route.terrain;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TiledPrefetchGateTest {
    private static final class CountingGate extends TiledPrefetchGate {
        final Map<Long, Integer> fetches = new HashMap<>();

        @Override
        protected CompletableFuture<Void> fetchTile(int tileX, int tileZ) {
            fetches.merge(((long) tileX << 32) | (tileZ & 0xFFFFFFFFL), 1, Integer::sum);
            return CompletableFuture.completedFuture(null);
        }
    }

    @Test
    void tileKeysUse32ChunkTilesWithFloorDivision() {
        assertNotEquals(TiledPrefetchGate.tileKey(-1, 0), TiledPrefetchGate.tileKey(0, 0));
        assertEquals(TiledPrefetchGate.tileKey(31, 31), TiledPrefetchGate.tileKey(0, 0));
        assertNotEquals(TiledPrefetchGate.tileKey(32, 0), TiledPrefetchGate.tileKey(0, 0));
    }

    @Test
    void sameTileIsFetchedOnce() {
        CountingGate gate = new CountingGate();
        gate.request(3, 4);
        gate.request(20, 30);

        assertEquals(1, gate.fetches.size());
        assertEquals(1, gate.fetches.values().iterator().next());
    }

    @Test
    void failedTileIsRetried() {
        int[] calls = {0};
        TiledPrefetchGate gate = new TiledPrefetchGate() {
            @Override
            protected CompletableFuture<Void> fetchTile(int tileX, int tileZ) {
                calls[0]++;
                return calls[0] == 1 ? CompletableFuture.failedFuture(new RuntimeException("network")) : CompletableFuture.completedFuture(null);
            }
        };

        assertTrue(gate.request(0, 0).isCompletedExceptionally());
        assertTrue(gate.request(0, 0).isDone());
        assertEquals(2, calls[0], "a failed tile is fetched again instead of being cached");
        assertTrue(!gate.request(0, 0).isCompletedExceptionally());
        assertEquals(2, calls[0], "a successful tile is cached");
    }

    @Test
    void corridorCoversBothSidesOncePerTile() {
        CountingGate gate = new CountingGate();
        List<CompletableFuture<Void>> futures = gate.requestCorridor(new BlockPos(0, 63, 0), new BlockPos(2048, 63, 0), 512);

        Set<Long> expected = new HashSet<>();
        for (int tx = 0; tx <= 4; tx++) {
            for (int tz = -1; tz <= 0; tz++) {
                expected.add(((long) tx << 32) | (tz & 0xFFFFFFFFL));
            }
        }
        assertTrue(gate.fetches.keySet().containsAll(expected), "missing tiles: " + gate.fetches.keySet());
        assertTrue(gate.fetches.values().stream().allMatch(c -> c == 1));
        assertEquals(gate.fetches.size(), futures.size());
    }
}
