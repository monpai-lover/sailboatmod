package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.route.terrain.TerrainSurfaceSampler;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** NBT first, cheap single-point terrain prediction second; never requests actual world generation. */
public final class CoarseWaterRouteWorld implements WaterRouteWorld {
    public static final int MAX_SAVED_CHUNKS = 4096;
    @FunctionalInterface public interface SavedChunks {
        Optional<NbtChunkWaterReader.ChunkColumns> read(int chunkX, int chunkZ);
    }
    @FunctionalInterface public interface RoutingChunks {
        NbtChunkWaterReader.ReadResult read(int chunkX, int chunkZ);
    }
    private final RoutingChunks saved;
    private final TerrainSurfaceSampler predicted;
    private final int seaLevel;
    private final Map<Long, NbtChunkWaterReader.ReadResult> chunks = new LinkedHashMap<>(128, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, NbtChunkWaterReader.ReadResult> eldest) {
            return size() > MAX_SAVED_CHUNKS;
        }
    };

    public CoarseWaterRouteWorld(SavedChunks saved, TerrainSurfaceSampler predicted, int seaLevel) {
        this(saved == null ? (cx, cz) -> NbtChunkWaterReader.ReadResult.absent()
                : (cx, cz) -> saved.read(cx, cz).map(NbtChunkWaterReader.ReadResult::ready)
                    .orElseGet(NbtChunkWaterReader.ReadResult::absent), predicted, seaLevel, true);
    }

    private CoarseWaterRouteWorld(RoutingChunks saved, TerrainSurfaceSampler predicted, int seaLevel, boolean typed) {
        this.saved = saved;
        this.predicted = predicted;
        this.seaLevel = seaLevel;
    }

    public static CoarseWaterRouteWorld realFirst(RoutingChunks saved, TerrainSurfaceSampler predicted, int seaLevel) {
        return new CoarseWaterRouteWorld(saved, predicted, seaLevel, true);
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        WaterRouteCancellation.check();
        if (policy == null || policy.boatHalfWidth() <= 0) return sampleColumn(x, z, policy);
        boolean unknown = false;
        WaterColumn anchor = null;
        for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
            WaterColumn column = sampleColumn(x + dx, z + dz, policy);
            if (column == null) { unknown = true; continue; }
            if (!column.passable()) return WaterColumn.blocked();
            if (anchor != null && anchor.surfacePos().getY() != column.surfacePos().getY()) return WaterColumn.blocked();
            if (anchor == null) anchor = column;
        }
        return unknown ? null : WaterColumn.passable(new BlockPos(x, anchor.surfacePos().getY(), z), 0);
    }

    private WaterColumn sampleColumn(int x, int z, WaterRoutePolicy policy) {
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        NbtChunkWaterReader.ReadResult result = chunks.get(key);
        if (result == null) {
            result = saved == null ? NbtChunkWaterReader.ReadResult.absent() : saved.read(cx, cz);
            // Never negative-cache unavailable disk data; a later slice must retry it.
            if (result.status() != NbtChunkWaterReader.ReadStatus.UNAVAILABLE) chunks.put(key, result);
        }
        if (result.status() == NbtChunkWaterReader.ReadStatus.UNAVAILABLE) return null;
        if (result.status() == NbtChunkWaterReader.ReadStatus.READY) {
            int index = (z & 15) * 16 + (x & 15);
            return result.columns().water()[index]
                    ? WaterColumn.passable(new BlockPos(x, result.columns().surfaceY()[index] + 1, z), 0)
                    : WaterColumn.blocked();
        }
        if (predicted == null) return null;
        return predicted.sample(x, z, Math.max(2, policy == null ? 2 : policy.stepSize())).map(surface -> surface.water()
                ? WaterColumn.passable(new BlockPos(x, surface.surfaceY() + 1, z), 0)
                : WaterColumn.blocked()).orElse(null);
    }
    @Override public boolean canLoadMoreChunks(int requested) { return false; }
    @Override public int consumedChunkLoads() { return 0; }
}
