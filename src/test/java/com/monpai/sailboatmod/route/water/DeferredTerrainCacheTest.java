package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class DeferredTerrainCacheTest {
    @Test void repeatedSlicesReuseOneInFlightReadWithoutBlocking() {
        AtomicInteger started = new AtomicInteger();
        CompletableFuture<String> io = new CompletableFuture<>();
        try (var cache = new DeferredTerrainCache<Integer, String>(key -> { started.incrementAndGet(); return io; }, v -> true, 16, 2)) {
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i=0;i<10_000;i++) assertNull(cache.poll(42));
            });
            assertEquals(1, started.get());
            io.complete("land");
            assertEquals("land", cache.poll(42));
            assertEquals("land", cache.poll(42));
            assertEquals(1, started.get());
        }
    }
    @Test void failedReadRetriesAfterBackoffAndIsNeverInventedAsWater() {
        AtomicLong clock = new AtomicLong(); AtomicInteger calls = new AtomicInteger();
        try (var cache = new DeferredTerrainCache<Integer,String>(k -> calls.incrementAndGet()==1
                ? CompletableFuture.failedFuture(new IllegalStateException("IO")) : CompletableFuture.completedFuture("land"),
                v -> true, 4, 2, clock::get)) {
            assertNull(cache.poll(1));
            for(int i=0;i<100;i++) assertNull(cache.poll(1));
            assertEquals(1,calls.get());
            clock.set(251_000_000L);
            assertEquals("land",cache.poll(1)); assertEquals(2,calls.get());
        }
    }
    @Test void rejectedUnavailableResultRemainsRetryable() {
        AtomicLong clock=new AtomicLong(); AtomicInteger calls=new AtomicInteger();
        try(var cache=new DeferredTerrainCache<Integer,String>(k -> CompletableFuture.completedFuture(
                calls.incrementAndGet()==1 ? "UNAVAILABLE" : "ABSENT"),v->!v.equals("UNAVAILABLE"),4,1,clock::get)) {
            assertNull(cache.poll(2)); clock.set(251_000_000L); assertEquals("ABSENT",cache.poll(2));
        }
    }
    @Test void saturationNeverRunsTheNextRequestInline() {
        AtomicInteger calls=new AtomicInteger(); CompletableFuture<String> io=new CompletableFuture<>();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->{calls.incrementAndGet();return io;},v->true,4,1)) {
            assertNull(cache.poll(1)); assertNull(cache.poll(2)); assertEquals(1,calls.get()); assertEquals(1,cache.pending());
        }
    }
    @Test void closeCancelsPendingRequestsAndLateResultsCannotRepopulateTheCache() {
        CompletableFuture<String> io=new CompletableFuture<>();
        var cache=new DeferredTerrainCache<Integer,String>(k->io,v->true,4,1);
        cache.poll(1);cache.close();assertTrue(io.isCancelled());assertNull(cache.poll(1));assertEquals(0,cache.size());
    }
    @Test void readyEntriesAreBoundedAndPendingEntriesAreNotEvicted() {
        CompletableFuture<String> io=new CompletableFuture<>();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->k==0?io:CompletableFuture.completedFuture("ready"),v->true,3,2)) {
            cache.poll(0); for(int k=1;k<100;k++)assertEquals("ready",cache.poll(k));
            assertTrue(cache.size()<=3);assertFalse(io.isCancelled());io.complete("late");assertEquals("late",cache.poll(0));
        }
    }
}
