package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebLoginThrottleTest {
    @Test
    void blocksAfterRepeatedFailuresUntilTheWindowPasses() {
        AtomicLong now = new AtomicLong(1_000_000L);
        MarketWebLoginThrottle throttle = new MarketWebLoginThrottle(5, 10 * 60_000L, now::get);
        for (int i = 0; i < 5; i++) {
            assertTrue(throttle.allowed("ip:203.0.113.9"), "attempt " + i);
            throttle.recordFailure("ip:203.0.113.9");
        }
        assertFalse(throttle.allowed("ip:203.0.113.9"));
        assertTrue(throttle.allowed("ip:198.51.100.1"), "other addresses are not affected");
        now.addAndGet(10 * 60_000L + 1);
        assertTrue(throttle.allowed("ip:203.0.113.9"), "the block lifts after the window");
    }

    @Test
    void successClearsTheCount() {
        AtomicLong now = new AtomicLong(0L);
        MarketWebLoginThrottle throttle = new MarketWebLoginThrottle(3, 60_000L, now::get);
        throttle.recordFailure("user:ada");
        throttle.recordFailure("user:ada");
        throttle.recordSuccess("user:ada");
        throttle.recordFailure("user:ada");
        throttle.recordFailure("user:ada");
        assertTrue(throttle.allowed("user:ada"));
    }

    @Test
    void forgetsOldEntriesSoMemoryStaysBounded() {
        AtomicLong now = new AtomicLong(0L);
        MarketWebLoginThrottle throttle = new MarketWebLoginThrottle(3, 1_000L, now::get);
        for (int i = 0; i < 10_000; i++) {
            throttle.recordFailure("ip:" + i);
        }
        now.addAndGet(2_000L);
        throttle.recordFailure("ip:new");
        assertTrue(throttle.trackedKeys() <= 1, "expired entries are dropped, tracked " + throttle.trackedKeys());
    }

    @Test
    void underPressureLockedKeysAreKeptAndUnlockedOnesGo() {
        AtomicLong now = new AtomicLong(0L);
        MarketWebLoginThrottle throttle = new MarketWebLoginThrottle(3, 60_000L, now::get, 1_000);
        for (int i = 0; i < 3; i++) {
            throttle.recordFailure("user:victim|ip:203.0.113.9");
        }
        assertFalse(throttle.allowed("user:victim|ip:203.0.113.9"));
        for (int i = 0; i < 5_000; i++) {
            throttle.recordFailure("ip:2001:db8::" + Integer.toHexString(i)); // rotating addresses
        }
        assertFalse(throttle.allowed("user:victim|ip:203.0.113.9"), "a flood of new keys must not unlock a locked one");
        assertTrue(throttle.trackedKeys() <= 1_001, "tracked " + throttle.trackedKeys());
    }

    @Test
    void forwardedAddressesAreTrustedOnlyFromConfiguredProxies() {
        java.util.Set<String> proxies = java.util.Set.of("127.0.0.1");
        assertEquals("198.51.100.7", MarketWebLoginThrottle.clientAddress("127.0.0.1", "198.51.100.7, 10.0.0.2", proxies));
        assertEquals("203.0.113.9", MarketWebLoginThrottle.clientAddress("203.0.113.9", "198.51.100.7", proxies),
                "a direct client cannot pick its own address");
        assertEquals("127.0.0.1", MarketWebLoginThrottle.clientAddress("127.0.0.1", null, proxies));
        assertEquals("127.0.0.1", MarketWebLoginThrottle.clientAddress("127.0.0.1", "198.51.100.7", java.util.Set.of()));
    }
}
