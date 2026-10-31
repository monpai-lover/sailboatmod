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
    private final ServerLevel level;
    private final java.util.concurrent.ConcurrentHashMap<Long, TerrainSurfaceSampler> samplers = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final DeferredTerrainCache<ChunkPos, NbtChunkWaterReader.ReadResult> saved;
    private final DeferredTerrainCache<Point, SurfaceSample> surfaces;
    private final DeferredTerrainCache<ChunkPos, Prediction> columns;

    RouteTerrainSource(ServerLevel level, TerrainPredictor predictor, Supplier<TerrainSurfaceSampler> surfaceFactory) {
        this.level = level;

        saved = new DeferredTerrainCache<>(pos -> submit(() -> NbtChunkWaterReader.readForRouting(
                level, pos.x, pos.z, 2000)), result -> result.status() != NbtChunkWaterReader.ReadStatus.UNAVAILABLE,
                4096, 8);
        surfaces = new DeferredTerrainCache<>(p -> submit(() -> {
            TerrainSurfaceSampler sampler = samplers.computeIfAbsent(Thread.currentThread().threadId(), ignored -> surfaceFactory.get());
            return sampler == null ? null : sampler.sample(p.x, p.z, p.spacing).orElse(null);
        }), value -> true, 65_536, 8);
        columns = new DeferredTerrainCache<>(pos -> submit(() -> predictor == null ? Prediction.notReady()
                : predictor.predictChunk(pos.x, pos.z, 0)), p -> p.status() == Prediction.Status.READY,
                4096, 8);
    }
    private <T> CompletableFuture<T> submit(Supplier<T> work) {
        if (closed.get()) return CompletableFuture.failedFuture(new java.util.concurrent.CancellationException());
        return PortAsyncRuntime.worker(level.getServer(), () -> WaterRouteCancellation.run(closed::get, work));
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
                + "} GENERATOR{" + columns.diagnostic() + "}";
    }
    @Override public void close() {
        closed.set(true);
        saved.close(); surfaces.close(); columns.close(); samplers.clear();
    }
}
