package com.monpai.sailboatmod.roadplanner.service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Loads the chunks along a planned road over several ticks instead of all at once on the main thread.
 * Generating a few thousand chunks synchronously froze the server long enough for the watchdog to kill it.
 *
 * Chunks are asked for a few per tick, with a bound on how many may be waiting at once, and are held
 * until {@link #release()} so they cannot unload before the road is sampled.
 */
public final class RoadChunkWarmup {
    public interface ChunkPort {
        /** Ask for the chunk to be loaded and kept loaded. */
        void request(long chunkKey);

        boolean isLoaded(long chunkKey);

        /** Undo {@link #request(long)}. */
        void release(long chunkKey);
    }

    public enum Status { LOADING, READY }

    private final ChunkPort port;
    private final int requestsPerTick;
    private final int maxInFlight;
    private final ArrayDeque<Long> notRequested;
    private final List<Long> pending = new ArrayList<>();
    private final List<Long> requested = new ArrayList<>();
    private final int total;
    private int loaded;
    private boolean released;

    public RoadChunkWarmup(Collection<Long> chunks, ChunkPort port, int requestsPerTick, int maxInFlight) {
        this.port = port;
        this.requestsPerTick = Math.max(1, requestsPerTick);
        this.maxInFlight = Math.max(1, maxInFlight);
        this.notRequested = new ArrayDeque<>(chunks);
        this.total = this.notRequested.size();
    }

    public Status tick() {
        if (released) {
            return loaded == total ? Status.READY : Status.LOADING;
        }
        int asked = 0;
        while (!notRequested.isEmpty() && asked < requestsPerTick && pending.size() < maxInFlight) {
            long chunk = notRequested.poll();
            port.request(chunk);
            requested.add(chunk);
            pending.add(chunk);
            asked++;
        }
        for (Iterator<Long> it = pending.iterator(); it.hasNext(); ) {
            if (port.isLoaded(it.next())) {
                it.remove();
                loaded++;
            }
        }
        return notRequested.isEmpty() && pending.isEmpty() ? Status.READY : Status.LOADING;
    }

    public int loaded() {
        return loaded;
    }

    public int total() {
        return total;
    }

    /** Frees every chunk this warmup asked for. Safe to call more than once. */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        for (long chunk : requested) {
            port.release(chunk);
        }
    }
}
