package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.Prediction;
import com.monpai.sailboatmod.route.terrain.SurfaceSample;
import com.monpai.sailboatmod.route.terrain.TerrainPredictor;
import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** IO/native calls run only on bounded PortIO workers; A* only polls cached futures.
 * Each native surface sampler belongs to one IO thread (its surface-block cache is mutable).
 * LOD has no full-resolution 512x512 prefetch barrier; the native surface API fetches what it needs.
 */
final class RouteTerrainSource implements AutoCloseable {
    private record Point(int x, int z, int spacing) { }
    @FunctionalInterface interface SavedReader {
        NbtChunkWaterReader.ReadResult read(int cx, int cz);
    }
    @FunctionalInterface interface AsyncSavedReader {
        CompletableFuture<NbtChunkWaterReader.ReadResult> read(int cx, int cz);
    }
    interface AsyncWork {
        <T> CompletableFuture<T> submit(Supplier<T> work);
    }
    private final AsyncWork executor;
    private final java.util.concurrent.ConcurrentHashMap<Long, TerrainSurfaceSampler> samplers = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final java.util.concurrent.Semaphore completedWork = new java.util.concurrent.Semaphore(0);
    private final DeferredTerrainCache<ChunkPos, NbtChunkWaterReader.ReadResult> saved;
    private final DeferredTerrainCache<Point, SurfaceSample> surfaces;
    private final DeferredTerrainCache<ChunkPos, Prediction> columns;
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.atomic.LongAdder> readOutcomes = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastReadFailure = "none";

    RouteTerrainSource(ServerLevel level, TerrainPredictor predictor, Supplier<TerrainSurfaceSampler> surfaceFactory) {
        this(null, (cx, cz) -> NbtChunkWaterReader.readForRoutingAsync(level, cx, cz, 2000),
                predictor, surfaceFactory, new AsyncWork() {
                    @Override public <T> CompletableFuture<T> submit(Supplier<T> work) {
                        return PortAsyncRuntime.worker(level.getServer(), work);
                    }
                });
    }

    /** Native integration tests use the same queues/cache/decoding without inventing a ServerLevel. */
    RouteTerrainSource(SavedReader reader, TerrainPredictor predictor,
                       Supplier<TerrainSurfaceSampler> surfaceFactory, AsyncWork executor) {
        this(reader, null, predictor, surfaceFactory, executor);
    }

    private RouteTerrainSource(SavedReader reader, AsyncSavedReader asyncReader, TerrainPredictor predictor,
                       Supplier<TerrainSurfaceSampler> surfaceFactory, AsyncWork executor) {
        this.executor = executor;
        saved = new DeferredTerrainCache<>(pos -> {
            if (closed.get()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
            CompletableFuture<NbtChunkWaterReader.ReadResult> request = asyncReader == null
                    ? submit(() -> reader.read(pos.x, pos.z)) : observe(asyncReader.read(pos.x, pos.z));
            request.whenComplete((value, error) -> {
                String outcome = error != null ? "exception" : value == null ? "empty_result"
                        : value.status() + "/" + value.generationStatus() + "/" + value.issue();
                readOutcomes.computeIfAbsent(outcome, ignored -> new java.util.concurrent.atomic.LongAdder()).increment();
                if (error != null || value == null || value.status() == NbtChunkWaterReader.ReadStatus.UNAVAILABLE)
                    lastReadFailure = pos + ":" + outcome;
            });
            return request;
        }, result -> result.status() != NbtChunkWaterReader.ReadStatus.UNAVAILABLE, 4096, 8);
        surfaces = new DeferredTerrainCache<>(p -> submit(() -> {
            TerrainSurfaceSampler sampler = samplers.computeIfAbsent(Thread.currentThread().threadId(), ignored -> surfaceFactory.get());
            return sampler == null ? null : sampler.sample(p.x, p.z, p.spacing).orElse(null);
        }), value -> true, 65_536, 16); // four hull cells for four outstanding candidates
        columns = new DeferredTerrainCache<>(pos -> submit(() -> predictor == null ? Prediction.notReady()
                : predictor instanceof com.monpai.sailboatmod.route.terrain.GeneratorColumnPredictor generator
                    ? generator.predictChunkDirect(pos.x, pos.z)
                    : predictor.predictChunk(pos.x, pos.z, 0)), p -> p.status() == Prediction.Status.READY,
                4096, 8);
    }
    private <T> CompletableFuture<T> submit(Supplier<T> work) {
        if (closed.get()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        return observe(executor.submit(() -> WaterRouteCancellation.run(closed::get, work)));
    }
    private <T> CompletableFuture<T> observe(CompletableFuture<T> result) {
        result.whenComplete((value, failure) -> {
            // Coalesce notifications. At most the concurrent completers can race here.
            if (completedWork.availablePermits() == 0) completedWork.release();
        });
        return result;
    }
    /** Route-worker wait, never a tick-thread wait. Fast completed futures should wake
     * the planner immediately rather than imposing 10ms on every tiny IO batch.
     */
    void awaitProgress(long maximumMillis) {
        WaterRouteCancellation.check();
        try { completedWork.tryAcquire(Math.max(1L, maximumMillis), java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); WaterRouteCancellation.check();
        }
    }
    NbtChunkWaterReader.ReadResult read(int cx, int cz) {
        var result = saved.poll(new ChunkPos(cx, cz));
        return result == null ? NbtChunkWaterReader.ReadResult.unavailable() : result;
    }
    Optional<SurfaceSample> surface(int x, int z, int spacing) {
        return Optional.ofNullable(surfaces.poll(new Point(x, z, spacing)));
    }
    Prediction predict(int cx, int cz) {
        Prediction result = columns.poll(new ChunkPos(cx, cz));
        return result == null ? Prediction.notReady() : result;
    }
    String diagnostic() {
        return "NBT{" + saved.diagnostic() + "} LOD{" + surfaces.diagnostic()
                + "} GENERATOR{" + columns.diagnostic() + "} NBT_RESULTS{" + new java.util.TreeMap<>(readOutcomes)
                + "} lastReadFailure=" + lastReadFailure;
    }
    @Override public void close() {
        closed.set(true);
        completedWork.release();
        saved.close(); surfaces.close(); columns.close(); samplers.clear();
    }
}
