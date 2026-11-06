package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapDecodedTileCacheTest {
    @Test
    void weightedLruEvictsTheLeastRecentlyUsedEntryWithinItsByteBudget() {
        var cache = new MarketWebMapDecodedTileCache(20);
        Path a = Path.of("world/a.png"), b = Path.of("world/b.png"), c = Path.of("world/c.png");
        byte[] encoded = {1, 2};
        cache.remember(a, encoded, new int[]{11, 12});
        cache.remember(b, encoded, new int[]{21, 22});
        assertEquals(20, cache.retainedBytes());
        assertArrayEquals(new int[]{11, 12}, cache.find(a, encoded).orElseThrow());
        cache.remember(c, encoded, new int[]{31, 32});
        assertTrue(cache.find(b, encoded).isEmpty());
        assertTrue(cache.find(a, encoded).isPresent());
        assertTrue(cache.find(c, encoded).isPresent());
        assertEquals(20, cache.retainedBytes());
    }

    @Test
    void bytesAndPixelsAreCopiedBothOnInsertionAndOnRead() {
        var cache = new MarketWebMapDecodedTileCache(100);
        Path file = Path.of("world/a.png");
        byte[] encoded = {1, 2, 3};
        int[] pixels = {11, 12};
        cache.remember(file, encoded, pixels);
        encoded[0] = 99;
        pixels[0] = 99;
        int[] first = cache.find(file, new byte[]{1, 2, 3}).orElseThrow();
        assertArrayEquals(new int[]{11, 12}, first);
        first[0] = 77;
        assertArrayEquals(new int[]{11, 12}, cache.find(file, new byte[]{1, 2, 3}).orElseThrow());
        assertTrue(cache.matches(file, new byte[]{1, 2, 3}, new int[]{11, 12}));
        assertFalse(cache.matches(file, new byte[]{1, 2, 3}, new int[]{11, 13}));
    }

    @Test
    void actualByteChangesInvalidateAnEntryAndOversizeEntriesNeverExceedCapacity() {
        var cache = new MarketWebMapDecodedTileCache(10);
        Path file = Path.of("world/a.png");
        cache.remember(file, new byte[]{1, 2}, new int[]{11, 12});
        assertEquals(10, cache.retainedBytes());
        assertTrue(cache.find(file, new byte[]{1, 3}).isEmpty());
        assertEquals(0, cache.retainedBytes());
        cache.remember(file, new byte[]{1, 2, 3}, new int[]{11, 12});
        assertEquals(0, cache.retainedBytes());
        assertTrue(cache.find(file, new byte[]{1, 2, 3}).isEmpty());
    }

    @Test
    void normalizedPathsShareAnEntryWhileDifferentWorldsRemainIsolated() {
        var cache = new MarketWebMapDecodedTileCache(100);
        byte[] encoded = {1};
        Path first = Path.of("world-a/map/0_0.png");
        cache.remember(first, encoded, new int[]{11});
        assertArrayEquals(new int[]{11}, cache.find(Path.of("world-a/map/../map/0_0.png"), encoded).orElseThrow());
        assertTrue(cache.find(Path.of("world-b/map/0_0.png"), encoded).isEmpty());
        cache.remove(first);
        assertEquals(0, cache.retainedBytes());
    }

    @Test
    void concurrentReadersAndWritersKeepTheCacheBoundedAndDoNotExposeMutableValues() throws Exception {
        var cache = new MarketWebMapDecodedTileCache(4096);
        try (var workers = Executors.newFixedThreadPool(4)) {
            var jobs = new ArrayList<Callable<Void>>();
            for (int worker = 0; worker < 4; worker++) {
                int id = worker;
                jobs.add(() -> {
                    for (int iteration = 0; iteration < 100; iteration++) {
                        Path path = Path.of("world_" + id + "/" + iteration % 8 + ".png");
                        cache.remember(path, new byte[]{1, 2}, new int[]{id, iteration});
                        cache.find(path, new byte[]{1, 2}).ifPresent(pixels -> pixels[0] = 999);
                        assertTrue(cache.retainedBytes() <= 4096);
                    }
                    return null;
                });
            }
            for (var result : workers.invokeAll(jobs)) result.get();
        }
        assertTrue(cache.retainedBytes() <= 4096);
    }
}
