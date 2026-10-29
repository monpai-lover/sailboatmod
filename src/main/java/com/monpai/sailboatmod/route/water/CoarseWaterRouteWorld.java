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
    private final SavedChunks saved;
    private final TerrainSurfaceSampler predicted;
    private final int seaLevel;
    private final Map<Long, Optional<NbtChunkWaterReader.ChunkColumns>> chunks = new LinkedHashMap<>(128, 0.75F, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, Optional<NbtChunkWaterReader.ChunkColumns>> eldest) {
            return size() > MAX_SAVED_CHUNKS;
        }
    };

    public CoarseWaterRouteWorld(SavedChunks saved, TerrainSurfaceSampler predicted, int seaLevel) {
        this.saved = saved;
        this.predicted = predicted;
        this.seaLevel = seaLevel;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        WaterRouteCancellation.check();
        int cx = Math.floorDiv(x, 16), cz = Math.floorDiv(z, 16);
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Optional<NbtChunkWaterReader.ChunkColumns> columns = chunks.computeIfAbsent(key,
                ignored -> saved == null ? Optional.empty() : saved.read(cx, cz));
        if (columns.isPresent()) {
            int index = (z & 15) * 16 + (x & 15);
            return columns.get().water()[index]
                    ? WaterColumn.passable(new BlockPos(x, seaLevel, z), 0)
                    : WaterColumn.blocked();
        }
        if (predicted == null) return null;
        return predicted.sample(x, z, Math.max(2, policy.stepSize())).map(surface -> surface.water()
                ? WaterColumn.passable(new BlockPos(x, seaLevel, z), 0)
                : WaterColumn.blocked()).orElse(null);
    }
    @Override public boolean canLoadMoreChunks(int requested) { return false; }
    @Override public int consumedChunkLoads() { return 0; }
}
