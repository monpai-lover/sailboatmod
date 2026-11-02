package com.monpai.sailboatmod.route.water;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
class DeferredTerrainCooldownTest {
    @Test void overflowingFailureWorkingSetCannotEvictCooldownAndStart12291Reads() {
        AtomicLong time=new AtomicLong();AtomicInteger starts=new AtomicInteger();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->{starts.incrementAndGet();return CompletableFuture.completedFuture(null);},v->true,4096,8,time::get)) {
            for(int round=0;round<3;round++)for(int key=0;key<4097;key++)assertNull(cache.poll(key));
            assertEquals(4096,starts.get());assertEquals(4096,cache.size());assertEquals(0,cache.pending());
        }
    }
    @Test void completedButUnharvestedFailuresAreNotEvictedAsSuccesses() {
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();CompletableFuture<String> failedLater=new CompletableFuture<>();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->{calls.incrementAndGet();return k==1?failedLater:CompletableFuture.completedFuture("water");},v->true,1,1,clock::get)) {
            cache.poll(1);failedLater.complete(null);assertNull(cache.poll(2));assertNull(cache.poll(1));assertEquals(1,calls.get());
            clock.set(251_000_000L);assertEquals("water",cache.poll(2));assertEquals(2,calls.get());
        }
    }
    @Test void persistentFailureBacksOffRatherThanRetrying600TimesIn150Seconds() {
        AtomicLong clock=new AtomicLong();AtomicInteger calls=new AtomicInteger();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->{calls.incrementAndGet();return CompletableFuture.completedFuture(null);},v->true,4,1,clock::get)) {
            for(int ms=0;ms<150_000;ms+=10){clock.set(ms*1_000_000L);cache.poll(1);}
            assertTrue(calls.get()<30,"calls="+calls.get());assertTrue(calls.get()>10);
        }
    }
    @Test void successEventuallyReplacesTheFailureWithoutInventingWater() {
        AtomicLong clock=new AtomicLong();AtomicBoolean healthy=new AtomicBoolean();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->CompletableFuture.completedFuture(healthy.get()?"actual-land":null),v->true,1,1,clock::get)) {
            assertNull(cache.poll(1));healthy.set(true);assertNull(cache.poll(1));clock.set(250_000_001L);
            assertEquals("actual-land",cache.poll(1));
        }
    }
    @Test void coolingEntryDoesNotEvictInFlightRequest() {
        AtomicLong clock=new AtomicLong();CompletableFuture<String> pending=new CompletableFuture<>();
        try(var cache=new DeferredTerrainCache<Integer,String>(k->k==0?pending:CompletableFuture.completedFuture(null),v->true,2,2,clock::get)) {
            cache.poll(0);cache.poll(1);cache.poll(2);assertFalse(pending.isCancelled());assertEquals(2,cache.size());
            pending.complete("real");assertEquals("real",cache.poll(0));
        }
    }
}
