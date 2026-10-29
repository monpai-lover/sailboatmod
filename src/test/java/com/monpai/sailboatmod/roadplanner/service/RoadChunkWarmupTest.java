package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadChunkWarmupTest {
    /** Loads a requested chunk a couple of ticks later, like the chunk system working off-thread. */
    private static final class FakeChunks implements RoadChunkWarmup.ChunkPort {
        final Set<Long> requested = new LinkedHashSet<>();
        final Set<Long> released = new HashSet<>();
        final Set<Long> loaded = new HashSet<>();
        final java.util.Map<Long, Integer> readyAt = new java.util.HashMap<>();
        int now;

        @Override public void request(long chunk) { requested.add(chunk); readyAt.put(chunk, now + 2); }
        @Override public boolean isLoaded(long chunk) { return loaded.contains(chunk) || readyAt.getOrDefault(chunk, Integer.MAX_VALUE) <= now; }
        @Override public void release(long chunk) { released.add(chunk); }
    }

    private static Set<Long> chunks(int count) {
        Set<Long> out = new LinkedHashSet<>();
        for (int i = 0; i < count; i++) out.add((long) i);
        return out;
    }

    @Test
    void requestsAFewChunksPerTickAndBecomesReadyOnceAllAreLoaded() {
        FakeChunks port = new FakeChunks();
        RoadChunkWarmup warmup = new RoadChunkWarmup(chunks(100), port, 16, 32);
        int ticks = 0;
        while (warmup.tick() != RoadChunkWarmup.Status.READY) {
            assertTrue(port.requested.size() <= 16 * (ticks + 1), "never more than 16 new requests per tick");
            port.now++;
            ticks++;
            assertTrue(ticks < 100, "warmup must finish");
        }
        assertEquals(100, warmup.loaded());
        assertEquals(100, warmup.total());
        assertEquals(100, port.requested.size());
    }

    @Test
    void keepsTheNumberOfChunksInFlightBounded() {
        FakeChunks port = new FakeChunks();
        port.readyAt.clear();
        RoadChunkWarmup warmup = new RoadChunkWarmup(chunks(500), port, 64, 40) ;
        // nothing ever loads: the warmup must stop asking once 40 are pending
        for (int tick = 0; tick < 20; tick++) {
            port.readyAt.replaceAll((chunk, at) -> Integer.MAX_VALUE);
            warmup.tick();
        }
        assertEquals(40, port.requested.size());
        assertEquals(RoadChunkWarmup.Status.LOADING, warmup.tick());
    }

    @Test
    void alreadyLoadedChunksAreReadyOnTheFirstTick() {
        FakeChunks port = new FakeChunks();
        port.loaded.addAll(chunks(10));
        RoadChunkWarmup warmup = new RoadChunkWarmup(chunks(10), port, 64, 256);
        assertEquals(RoadChunkWarmup.Status.READY, warmup.tick());
    }

    @Test
    void releaseFreesEveryRequestedChunkOnce() {
        FakeChunks port = new FakeChunks();
        RoadChunkWarmup warmup = new RoadChunkWarmup(chunks(50), port, 20, 256);
        warmup.tick();
        warmup.release();
        warmup.release();
        assertEquals(port.requested, port.released);
        assertEquals(20, port.released.size());
        // a released warmup asks for nothing more
        warmup.tick();
        assertEquals(20, port.requested.size());
    }
}
