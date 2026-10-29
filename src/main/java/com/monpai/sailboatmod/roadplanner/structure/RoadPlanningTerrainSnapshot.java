package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristics;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerStagedPlan;
import com.monpai.sailboatmod.route.terrain.GeneratorColumnPredictor;
import com.monpai.sailboatmod.util.OfflineChunkNbtReader;
import com.monpai.sailboatmod.util.OfflineChunkPalette;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/** Worker-owned columns: immutable loaded copies, then existing FULL NBT, then native generator prediction. */
public final class RoadPlanningTerrainSnapshot implements RoadTerrainSampler {
    private final BiFunction<Integer, Integer, Optional<NoiseColumn>> existing;
    private final GeneratorColumnPredictor.ColumnSource predicted;
    private final int minY, maxY, seaLevel;
    private final LinkedHashMap<Long, NoiseColumn> columns = new LinkedHashMap<>(256, 0.75F, true);

    public RoadPlanningTerrainSnapshot(BiFunction<Integer, Integer, Optional<NoiseColumn>> existing,
                                       GeneratorColumnPredictor.ColumnSource predicted, int minY, int maxY, int seaLevel) {
        this.existing = existing;
        this.predicted = predicted;
        this.minY = minY;
        this.maxY = maxY;
        this.seaLevel = seaLevel;
    }

    /** Capture only on the server thread. Palette copies sever the worker's access to mutable world sections. */
    public static RoadPlanningTerrainSnapshot capture(ServerLevel level, RoadPlannerStagedPlan.Stage stage) {
        int minY = level.getMinBuildHeight(), maxY = level.getMaxBuildHeight();
        Map<Long, PalettedContainer<BlockState>[]> loaded = new HashMap<>();
        for (long key : stage.chunks()) {
            var chunk = level.getChunkSource().getChunkNow(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (chunk == null) continue;
            var sections = chunk.getSections();
            @SuppressWarnings("unchecked") PalettedContainer<BlockState>[] copies = new PalettedContainer[sections.length];
            for (int i = 0; i < sections.length; i++) if (sections[i] != null) copies[i] = sections[i].getStates().copy();
            loaded.put(key, copies);
        }
        Map<Long, Optional<OfflineChunkPalette.OfflineChunkBlocks>> saved = new HashMap<>();
        Map<CompoundTag, BlockState> states = new HashMap<>();
        int minSection = Math.floorDiv(minY, 16);
        BiFunction<Integer, Integer, Optional<NoiseColumn>> actual = (x, z) -> {
            checkCancelled();
            long key = ChunkPos.asLong(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
            PalettedContainer<BlockState>[] copies = loaded.get(key);
            if (copies != null) return Optional.of(column(minY, maxY, y -> {
                int section = Math.floorDiv(y, 16) - minSection;
                return section < 0 || section >= copies.length || copies[section] == null ? Blocks.AIR.defaultBlockState()
                        : copies[section].get(x & 15, y & 15, z & 15);
            }));
            Optional<OfflineChunkPalette.OfflineChunkBlocks> blocks = saved.computeIfAbsent(key, ignored ->
                    OfflineChunkNbtReader.readSafeFromWorkerThread(level, new ChunkPos(key), 1000, false)
                            .map(tag -> OfflineChunkPalette.decode(tag, minY, maxY)));
            // This cache belongs to one <=192-block sampling window and is discarded with its worker task.
            if (blocks.isEmpty()) return Optional.empty();
            return Optional.of(column(minY, maxY, y -> state(blocks.get().paletteTag(x & 15, y, z & 15), states)));
        };
        var generator = level.getChunkSource().getGenerator();
        var randomState = level.getChunkSource().randomState();
        LevelHeightAccessor bounds = LevelHeightAccessor.create(minY, maxY - minY);
        return new RoadPlanningTerrainSnapshot(actual, (x, z) -> generator.getBaseColumn(x, z, bounds, randomState),
                minY, maxY, level.getSeaLevel());
    }

    public static RoadTerrainSampler fromSavedChunk(CompoundTag tag, int chunkX, int chunkZ, int minY, int maxY) {
        var blocks = OfflineChunkPalette.decode(tag, minY, maxY);
        Map<CompoundTag, BlockState> states = new HashMap<>();
        return new RoadPlanningTerrainSnapshot((x, z) -> Math.floorDiv(x, 16) == chunkX && Math.floorDiv(z, 16) == chunkZ
                && OfflineChunkPalette.isFullStatus(tag)
                ? Optional.of(column(minY, maxY, y -> state(blocks.paletteTag(x & 15, y, z & 15), states))) : Optional.empty(),
                (x, z) -> null, minY, maxY, 63);
    }

    private static BlockState state(CompoundTag tag, Map<CompoundTag, BlockState> states) {
        return tag == null ? Blocks.AIR.defaultBlockState() : states.computeIfAbsent(tag,
                key -> NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), key));
    }

    private static NoiseColumn column(int minY, int maxY, IntFunction<BlockState> state) {
        BlockState[] values = new BlockState[maxY - minY];
        for (int y = minY; y < maxY; y++) values[y - minY] = state.apply(y);
        return new NoiseColumn(minY, values);
    }

    private NoiseColumn column(int x, int z) {
        checkCancelled();
        long key = ChunkPos.asLong(x, z);
        NoiseColumn cached = columns.get(key);
        if (cached != null) return cached;
        Optional<NoiseColumn> actual = existing.apply(x, z);
        checkCancelled();
        NoiseColumn result = actual.orElseGet(() -> predicted.column(x, z));
        checkCancelled();
        if (result == null) throw new IllegalStateException("道路地形数据尚不可用：" + x + "," + z);
        columns.put(key, result);
        while (columns.size() > 4096) columns.remove(columns.keySet().iterator().next());
        return result;
    }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Road planning cancelled");
    }

    private int top(NoiseColumn column) {
        for (int y = maxY - 1; y >= minY; y--) if (!column.getBlock(y).isAir()) return y + 1;
        return minY;
    }

    @Override public int terrainY(int x, int z) {
        NoiseColumn column = column(x, z);
        return RoadTerrainColumnSampler.terrainYFromColumn(top(column), minY, column::getBlock);
    }
    @Override public int waterSurfaceY(int x, int z) {
        NoiseColumn column = column(x, z);
        return RoadTerrainColumnSampler.waterSurfaceYFromColumn(top(column), minY, seaLevel, column::getBlock);
    }
    @Override public int oceanFloorY(int x, int z) {
        NoiseColumn column = column(x, z);
        for (int y = top(column) - 1; y >= minY; y--) {
            BlockState state = column.getBlock(y);
            if (!state.isAir() && state.getFluidState().isEmpty() && !RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state)) return y + 1;
        }
        return minY;
    }
    @Override public boolean isWater(int x, int y, int z) {
        return y >= minY && y < maxY && !column(x, z).getBlock(y).getFluidState().isEmpty();
    }
}
