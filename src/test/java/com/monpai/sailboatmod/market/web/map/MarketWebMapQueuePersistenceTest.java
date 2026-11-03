package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapConstants;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMapQueuePersistenceTest {
    @TempDir Path tempDir;

    @Test
    void chunkSaveReleasesQueueMonitorWhileWritingAndRetainsSnapshotOrder() throws Exception {
        Path file = tempDir.resolve("dirty_chunks.json");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean heldMonitor = new AtomicBoolean();
        AtomicReference<MarketWebMapDirtyChunkQueue> queueRef = new AtomicReference<>();
        MarketWebMapDirtyChunkQueue queue = new MarketWebMapDirtyChunkQueue((destination, json) -> {
            heldMonitor.set(Thread.holdsLock(queueRef.get()));
            started.countDown();
            await(release);
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        queueRef.set(queue);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 3, 4);
        CompletableFuture<Boolean> saving = CompletableFuture.supplyAsync(() -> queue.save(file));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(heldMonitor.get(), "disk IO must not hold the dirty queue monitor");
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                queue.clear();
                queue.markDirty(MarketWebMapConstants.OVERWORLD, 9, 10);
            });
        } finally {
            release.countDown();
            assertTrue(saving.get(2, TimeUnit.SECONDS));
        }
        MarketWebMapDirtyChunkQueue loaded = new MarketWebMapDirtyChunkQueue();
        assertEquals(2, loaded.load(file));
        assertEquals(List.of(
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 1, 2),
                new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 3, 4)), loaded.poll(2));
        assertEquals(1, queue.size());
    }

    @Test
    void regionSaveReleasesQueueMonitorAndKeepsCurrentCursorWithPendingRegions() throws Exception {
        Path file = tempDir.resolve("dirty_regions.json");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean heldMonitor = new AtomicBoolean();
        AtomicReference<MarketWebMapDirtyRegionQueue> queueRef = new AtomicReference<>();
        MarketWebMapDirtyRegionQueue queue = new MarketWebMapDirtyRegionQueue((destination, json) -> {
            heldMonitor.set(Thread.holdsLock(queueRef.get()));
            started.countDown();
            await(release);
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        queueRef.set(queue);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, -3, 4);
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        queue.expandChunks(17);
        CompletableFuture<Boolean> saving = CompletableFuture.supplyAsync(() -> queue.save(file));
        try {
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertFalse(heldMonitor.get(), "disk IO must not hold the dirty queue monitor");
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> queue.expandChunks(5));
        } finally {
            release.countDown();
            assertTrue(saving.get(2, TimeUnit.SECONDS));
        }
        MarketWebMapDirtyRegionQueue loaded = new MarketWebMapDirtyRegionQueue();
        assertEquals(2, loaded.load(file));
        assertEquals(17, loaded.inProgressLocalChunk());
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -79, 128),
                loaded.expandChunks(1).getFirst());
        assertEquals(22, queue.inProgressLocalChunk());
    }

    @Test
    void asynchronousSaveSnapshotsLatestQueueAfterAnInFlightWrite() throws Exception {
        Path file = tempDir.resolve("dirty_chunks.json");
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        MarketWebMapDirtyChunkQueue queue = new MarketWebMapDirtyChunkQueue((destination, json) -> {
            if (first.getAndSet(false)) {
                started.countDown();
                await(release);
            }
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        queue.markDirty(MarketWebMapConstants.OVERWORLD, 1, 2);
        try {
            assertTrue(queue.requestSave(writer, file));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                queue.clear();
                queue.markDirty(MarketWebMapConstants.OVERWORLD, 9, 10);
                for (int i = 0; i < 100; i++) assertTrue(queue.requestSave(writer, file));
            });
            release.countDown();
            assertTrue(writer.flush(2000));
            MarketWebMapDirtyChunkQueue loaded = new MarketWebMapDirtyChunkQueue();
            assertEquals(1, loaded.load(file));
            assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, 9, 10),
                    loaded.poll(1).getFirst());
        } finally {
            release.countDown();
            assertTrue(writer.shutdown(2000));
        }
    }

    @Test
    void jointSaveKeepsRegionCursorBehindChunksUntilTheMatchingChunkSnapshotIsWritten() throws Exception {
        Object stateLock = new Object();
        Path chunksFile = tempDir.resolve("dirty_chunks.json");
        Path regionsFile = tempDir.resolve("dirty_regions.json");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch releaseSecond = new CountDownLatch(1);
        AtomicInteger chunkWrites = new AtomicInteger();
        AtomicBoolean ioHeldStateLock = new AtomicBoolean();
        MarketWebMapDirtyChunkQueue chunks = new MarketWebMapDirtyChunkQueue((destination, json) -> {
            ioHeldStateLock.set(ioHeldStateLock.get() || Thread.holdsLock(stateLock));
            if (chunkWrites.incrementAndGet() == 1) {
                firstStarted.countDown();
                await(releaseFirst);
            } else {
                secondStarted.countDown();
                await(releaseSecond);
            }
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        MarketWebMapDirtyRegionQueue regions = new MarketWebMapDirtyRegionQueue();
        regions.markDirty(MarketWebMapConstants.OVERWORLD, 0, 0);
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(2, 10);
        try {
            assertTrue(writer.requestDirtyQueues(stateLock, chunks, chunksFile, regions, regionsFile));
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                synchronized (stateLock) {
                    for (var chunk : regions.expandChunks(32)) {
                        chunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
                    }
                    assertTrue(writer.requestDirtyQueues(stateLock, chunks, chunksFile, regions, regionsFile));
                }
            });
            releaseFirst.countDown();
            assertTrue(secondStarted.await(2, TimeUnit.SECONDS));
            MarketWebMapDirtyRegionQueue recoveredRegions = new MarketWebMapDirtyRegionQueue();
            assertEquals(1, recoveredRegions.load(regionsFile));
            assertEquals(0, recoveredRegions.inProgressLocalChunk(),
                    "an interrupted newer save must replay the region instead of skipping unwritten chunks");
            MarketWebMapDirtyChunkQueue recoveredChunks = new MarketWebMapDirtyChunkQueue();
            assertEquals(0, recoveredChunks.load(chunksFile));
            assertFalse(ioHeldStateLock.get(), "joint save must release manager lock before IO");
            releaseSecond.countDown();
            assertTrue(writer.flush(2000));
            assertEquals(32, recoveredChunks.load(chunksFile));
            assertEquals(1, recoveredRegions.load(regionsFile));
            assertEquals(0, recoveredRegions.inProgressLocalChunk(), "persistent recovery replays the region conservatively");
            assertEquals(32, regions.inProgressLocalChunk(), "saving must not rewind live progress");
        } finally {
            releaseFirst.countDown();
            releaseSecond.countDown();
            assertTrue(writer.shutdown(2000));
        }
    }

    @Test
    void jointSaveUsesOneDestinationSlotAndDoesNotAdvanceRegionsWhenChunkWriteFails() {
        Object stateLock = new Object();
        AtomicInteger chunkAttempts = new AtomicInteger();
        AtomicInteger regionWrites = new AtomicInteger();
        MarketWebMapDirtyChunkQueue chunks = new MarketWebMapDirtyChunkQueue((destination, json) -> {
            if (chunkAttempts.incrementAndGet() == 1) throw new IOException("temporary chunk save failure");
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        MarketWebMapDirtyRegionQueue regions = new MarketWebMapDirtyRegionQueue((destination, json) -> {
            regionWrites.incrementAndGet();
            assertFalse(Thread.holdsLock(stateLock));
            MarketWebMapStateWriter.writeJson(destination, json);
        });
        regions.markDirty(MarketWebMapConstants.OVERWORLD, 0, 0);
        for (var chunk : regions.expandChunks(32)) chunks.markDirty(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ());
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter(1, 10);
        assertTrue(writer.requestDirtyQueues(stateLock, chunks, tempDir.resolve("chunks.json"),
                regions, tempDir.resolve("regions.json")));
        assertTrue(writer.shutdown(2000));
        assertEquals(2, chunkAttempts.get());
        assertEquals(1, regionWrites.get(), "regions must run only after the corresponding chunk write succeeds");
    }

    @Test
    void jointRecoveryIncludesFullyExpandedRegionsAndTasksClaimedBeforeSubmission() {
        Object stateLock = new Object();
        MarketWebMapDirtyChunkQueue chunks = new MarketWebMapDirtyChunkQueue();
        MarketWebMapDirtyRegionQueue regions = new MarketWebMapDirtyRegionQueue();
        MarketWebMapRenderQueue accepted = new MarketWebMapRenderQueue(2048);
        regions.markDirty(MarketWebMapConstants.OVERWORLD, -1, 2);
        for (var chunk : regions.expandChunks(1024)) {
            accepted.enqueue(chunk.dimensionId(), chunk.chunkX(), chunk.chunkZ(), 0);
        }
        assertEquals(0, regions.size(), "the live dirty cursor has completely expanded");
        var claimed = accepted.pollTracked(1, 0).getFirst();
        Path chunksFile = tempDir.resolve("accepted_chunks.json");
        Path regionsFile = tempDir.resolve("accepted_regions.json");
        MarketWebMapStateWriter writer = new MarketWebMapStateWriter();
        assertTrue(writer.requestDirtyQueues(stateLock, chunks, chunksFile, regions, regionsFile, () -> {
            assertTrue(Thread.holdsLock(stateLock), "accepted-work snapshots must share the mutation lock");
            return accepted.snapshot().stream().map(task -> new MarketWebMapDirtyRegionQueue.RegionCoordinate(
                    task.dimensionId(), Math.floorDiv(task.chunkX(), 32), Math.floorDiv(task.chunkZ(), 32))).toList();
        }));
        assertTrue(writer.shutdown(2000));
        MarketWebMapDirtyRegionQueue recovered = new MarketWebMapDirtyRegionQueue();
        assertEquals(1, recovered.load(regionsFile), "a fully expanded region still has accepted queue work");
        assertEquals(new MarketWebMapDirtyChunkQueue.ChunkCoordinate(MarketWebMapConstants.OVERWORLD, -32, 64),
                recovered.expandChunks(1).getFirst());
        assertTrue(accepted.snapshot().contains(claimed));
        assertEquals(0, regions.size(), "persisting conservative recovery must not alter live queues");
    }

    private static void await(CountDownLatch latch) throws IOException {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("timed out waiting for test latch");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(exception);
        }
    }
}
