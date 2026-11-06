package com.monpai.sailboatmod.market.web.map;

import com.google.gson.JsonElement;
import com.google.gson.Gson;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.Collection;
import java.util.function.Supplier;

/**
 * One bounded, coalescing lane for recoverable map state. Callers only enqueue references to
 * immutable state (or a supplier that snapshots a synchronized queue on this worker).
 * Serialization, filesystem operations and callbacks never run under this writer's monitor.
 */
public final class MarketWebMapStateWriter {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private final Object monitor = new Object();
    private final LinkedHashMap<Path, PendingWrite> pending = new LinkedHashMap<>();
    private final int maxDestinations;
    private final long retryDelayNanos;
    private final Thread worker;
    private Path inFlight;
    private boolean accepting = true;
    private boolean stopping;
    private boolean terminated;
    private long stopDeadline;

    public MarketWebMapStateWriter() {
        this(16, 250);
    }

    MarketWebMapStateWriter(int maxDestinations, long retryDelayMillis) {
        this.maxDestinations = Math.max(1, maxDestinations);
        this.retryDelayNanos = TimeUnit.MILLISECONDS.toNanos(Math.max(1, retryDelayMillis));
        worker = new Thread(this::run, "sailboatmod-map-state");
        worker.setDaemon(true);
        worker.start();
    }

    /** Replaces the pending operation for this destination; never waits for disk IO. */
    public boolean request(Path file, WriteOperation operation) {
        if (file == null || operation == null) return false;
        Path destination = file.toAbsolutePath().normalize();
        synchronized (monitor) {
            if (!accepting) return false;
            boolean known = destination.equals(inFlight) || pending.containsKey(destination);
            if (!known && pendingDestinationsLocked() >= maxDestinations) return false;
            pending.put(destination, new PendingWrite(operation, 0, 0));
            monitor.notifyAll();
            return true;
        }
    }

    /** An ordered tombstone: an older in-flight save cannot recreate the file after this. */
    public boolean delete(Path file) {
        if (file == null) return false;
        Path destination = file.toAbsolutePath().normalize();
        return request(destination, () -> Files.deleteIfExists(destination));
    }

    /**
     * Captures a consistent dirty-state pair under the caller's mutation lock, then releases
     * every state monitor before encoding or writing. Chunks must succeed before region cursors
     * advance on disk, so interruption between files leaves conservative replay work. Durable
     * regions always restart at zero and include accepted queue/probe/render work supplied by
     * the manager; the live cursor may have advanced beyond work not yet written as images.
     * A pair occupies one coalescing slot; callers must use this instead of independent saves.
     */
    public boolean requestDirtyQueues(Object stateLock,
                                      MarketWebMapDirtyChunkQueue chunks, Path chunksFile,
                                      MarketWebMapDirtyRegionQueue regions, Path regionsFile) {
        return requestDirtyQueues(stateLock, chunks, chunksFile, regions, regionsFile, List::of);
    }

    public boolean requestDirtyQueues(Object stateLock,
                                      MarketWebMapDirtyChunkQueue chunks, Path chunksFile,
                                      MarketWebMapDirtyRegionQueue regions, Path regionsFile,
                                      Supplier<? extends Collection<MarketWebMapDirtyRegionQueue.RegionCoordinate>> recoveryRegions) {
        if (stateLock == null || chunks == null || chunksFile == null || regions == null || regionsFile == null
                || recoveryRegions == null) {
            return false;
        }
        return request(chunksFile, () -> {
            MarketWebMapDirtyChunkQueue.Snapshot chunkSnapshot;
            MarketWebMapDirtyRegionQueue.Snapshot regionSnapshot;
            synchronized (stateLock) {
                chunkSnapshot = chunks.snapshotForSave();
                var additional = recoveryRegions.get();
                List<MarketWebMapDirtyRegionQueue.RegionCoordinate> accepted = additional == null
                        ? new ArrayList<>() : new ArrayList<>(additional);
                for (var chunk : chunkSnapshot.chunks()) {
                    accepted.add(new MarketWebMapDirtyRegionQueue.RegionCoordinate(chunk.dimensionId(),
                            Math.floorDiv(chunk.chunkX(), 32), Math.floorDiv(chunk.chunkZ(), 32)));
                }
                regionSnapshot = regions.snapshotForRecovery(accepted);
            }
            chunks.writeSnapshot(chunksFile, chunkSnapshot);
            regions.writeSnapshot(regionsFile, regionSnapshot);
        });
    }


    /** Waits for accepted work, including retries and replacements received during a write. */
    public boolean flush(long timeoutMillis) {
        long deadline = deadline(timeoutMillis);
        synchronized (monitor) {
            while (pendingDestinationsLocked() != 0 && !terminated) {
                if (!await(deadline)) return false;
            }
            return pendingDestinationsLocked() == 0;
        }
    }

    /**
     * Stops acceptance and drains up to the deadline. A blocked atomic write may finish after a
     * false result; no further pending operation starts after the deadline. A false result is
     * logged and leaves the last successfully persisted state available for restart recovery.
     */
    public boolean shutdown(long timeoutMillis) {
        boolean drained;
        int remaining;
        synchronized (monitor) {
            if (!stopping) {
                accepting = false;
                stopping = true;
                stopDeadline = deadline(timeoutMillis);
                monitor.notifyAll();
            }
            long waitDeadline = deadline(timeoutMillis);
            while (!terminated) {
                if (!await(waitDeadline)) break;
            }
            remaining = pendingDestinationsLocked();
            drained = remaining == 0;
        }
        if (!drained) LOGGER.warn("Market web map state shutdown left {} destination(s) unwritten", remaining);
        return drained;
    }

    /** Includes an in-flight destination once, even when a newer replacement is pending. */
    public int pendingDestinations() {
        synchronized (monitor) {
            return pendingDestinationsLocked();
        }
    }

    private int pendingDestinationsLocked() {
        return pending.size() + (inFlight == null || pending.containsKey(inFlight) ? 0 : 1);
    }

    private void run() {
        while (true) {
            Path destination;
            PendingWrite write;
            synchronized (monitor) {
                Map.Entry<Path, PendingWrite> next;
                while ((next = nextReady()) == null) {
                    if (stopping && (pending.isEmpty() || System.nanoTime() - stopDeadline >= 0)) {
                        terminated = true;
                        monitor.notifyAll();
                        return;
                    }
                    long nextWake = stopping ? stopDeadline : Long.MAX_VALUE;
                    for (PendingWrite queued : pending.values()) nextWake = Math.min(nextWake, queued.retryAt());
                    try {
                        if (nextWake == Long.MAX_VALUE) monitor.wait();
                        else TimeUnit.NANOSECONDS.timedWait(monitor, Math.max(1, nextWake - System.nanoTime()));
                    } catch (InterruptedException exception) {
                        // A terminated worker must not acknowledge new work it cannot execute.
                        accepting = false;
                        terminated = true;
                        monitor.notifyAll();
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                destination = next.getKey();
                write = pending.remove(destination);
                inFlight = destination;
            }
            Exception failure = null;
            try {
                write.operation().write();
            } catch (IOException | RuntimeException exception) {
                failure = exception;
            }
            synchronized (monitor) {
                inFlight = null;
                // A newer request supersedes a failed one; never put the old value back over it.
                if (failure != null && !pending.containsKey(destination)) {
                    pending.put(destination, new PendingWrite(write.operation(),
                            System.nanoTime() + retryDelayNanos, write.failures() + 1));
                }
                monitor.notifyAll();
            }
            // Rate-limit repeated failures while retaining latest state for a later retry.
            if (failure != null && (write.failures() == 0 || (write.failures() & 63) == 63)) {
                LOGGER.warn("Failed to persist market web map state to {}; retrying latest state", destination, failure);
            }
        }
    }

    private Map.Entry<Path, PendingWrite> nextReady() {
        long now = System.nanoTime();
        if (stopping && now - stopDeadline >= 0) return null;
        for (Map.Entry<Path, PendingWrite> entry : pending.entrySet()) {
            if (entry.getValue().retryAt() == 0 || now - entry.getValue().retryAt() >= 0) return entry;
        }
        return null;
    }

    private boolean await(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || Thread.currentThread() == worker) return false;
        try {
            TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
            return true;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static long deadline(long timeoutMillis) {
        return System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(timeoutMillis, 86_400_000)));
    }

    /** Same JSON schema as the old queues; unique temporary files also make sync saves safe. */
    public static void writeJson(Path file, JsonElement value) throws IOException {
        Path destination = file.toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Path temporary = Files.createTempFile(destination.getParent(), "map-state-", ".tmp");
        try {
            try (Writer output = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(value, output);
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicFailure) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @FunctionalInterface
    public interface WriteOperation {
        void write() throws IOException;
    }

    @FunctionalInterface
    interface JsonFileWriter {
        void write(Path file, JsonElement value) throws IOException;
    }

    private record PendingWrite(WriteOperation operation, long retryAt, int failures) { }
}
