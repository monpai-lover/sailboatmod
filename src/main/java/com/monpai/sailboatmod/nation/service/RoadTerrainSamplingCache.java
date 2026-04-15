package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RoadTerrainSamplingCache {
    private final Level level;
    private final Map<Long, TerrainColumn> columns = new HashMap<>();

    public RoadTerrainSamplingCache(Level level) {
        this.level = level;
    }

    public TerrainColumn sample(int x, int z) {
        long key = BlockPos.asLong(x, 0, z);
        return columns.computeIfAbsent(key, ignored -> {
            BlockPos surfacePos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below();
            BlockState surfaceState = level.getBlockState(surfacePos);
            boolean water = surfaceState.liquid() || !surfaceState.getFluidState().isEmpty();
            return new TerrainColumn(surfacePos, surfaceState, water);
        });
    }

    public void seedColumn(int x, int z, TerrainColumn column) {
        if (column == null) {
            return;
        }
        columns.put(BlockPos.asLong(x, 0, z), column);
    }

    public void seedColumns(Map<Long, TerrainColumn> seededColumns) {
        if (seededColumns == null || seededColumns.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, TerrainColumn> entry : seededColumns.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                columns.put(entry.getKey(), entry.getValue());
            }
        }
    }

    public Map<Long, TerrainColumn> snapshotColumns() {
        return columns.isEmpty() ? Map.of() : Map.copyOf(new LinkedHashMap<>(columns));
    }

    public record TerrainColumn(BlockPos surfacePos, BlockState surfaceState, boolean water) {
    }
}
