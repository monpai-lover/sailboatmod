package com.monpai.sailboatmod.market.web.map;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapPublicDataCacheTest {
    @Test void invalidationRebuildsPublicDataBeforeTheTtlExpires() {
        AtomicLong clock = new AtomicLong(100L);
        AtomicInteger builds = new AtomicInteger();
        var cache = new MarketWebMapPublicDataCache<Integer>(2_000_000_000L, clock::get);
        Object world = new Object();
        assertEquals(1, cache.get(world, builds::incrementAndGet));

        cache.invalidate();

        assertEquals(2, cache.get(world, builds::incrementAndGet),
                "removing a terminal must evict its public marker immediately");
        assertEquals(2, cache.get(world, builds::incrementAndGet));
    }

    @Test void repeatedPublicRequestsShareOneBuildUntilExpiry() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger builds = new AtomicInteger();
        var cache = new MarketWebMapPublicDataCache<Integer>(2_000_000_000L, clock::get);
        Object world = new Object();
        assertEquals(1, cache.get(world, builds::incrementAndGet));
        for (int i = 0; i < 100; i++) assertEquals(1, cache.get(world, builds::incrementAndGet));
        clock.set(2_000_000_000L);
        assertEquals(2, cache.get(world, builds::incrementAndGet));
    }

    @Test void worldChangeAndClockRollbackCannotServeThePreviousSnapshot() {
        AtomicLong clock = new AtomicLong(100);
        AtomicInteger builds = new AtomicInteger();
        var cache = new MarketWebMapPublicDataCache<Integer>(200, clock::get);
        Object first = new Object(), second = new Object();
        assertEquals(1, cache.get(first, builds::incrementAndGet));
        assertEquals(2, cache.get(second, builds::incrementAndGet));
        clock.set(99);
        assertEquals(3, cache.get(second, builds::incrementAndGet));
    }

    @Test void failedBuildDoesNotPublishAnotherWorldsData() {
        var cache = new MarketWebMapPublicDataCache<String>(200, () -> 0L);
        Object first = new Object(), second = new Object();
        assertEquals("first", cache.get(first, () -> "first"));
        assertThrows(IllegalStateException.class, () -> cache.get(second, () -> { throw new IllegalStateException(); }));
        assertEquals("second", cache.get(second, () -> "second"));
    }
}
