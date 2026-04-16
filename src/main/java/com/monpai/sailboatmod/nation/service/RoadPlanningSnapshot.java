package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record RoadPlanningSnapshot(Map<Long, ColumnSample> columns,
                                   boolean sourceIslandLike,
                                   boolean targetIslandLike,
                                   List<BlockPos> bridgeHeadCandidates,
                                   BlockPos start,
                                   BlockPos end) {
    public RoadPlanningSnapshot {
        columns = columns == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(columns));
        bridgeHeadCandidates = bridgeHeadCandidates == null ? List.of() : List.copyOf(bridgeHeadCandidates);
        start = start == null ? null : start.immutable();
        end = end == null ? null : end.immutable();
    }

    public ColumnSample column(int x, int z) {
        return columns.get(BlockPos.asLong(x, 0, z));
    }

    public boolean islandLikeAtEitherEndpoint() {
        return sourceIslandLike || targetIslandLike;
    }

    public Map<Long, RoadTerrainSamplingCache.TerrainColumn> terrainColumns() {
        if (columns.isEmpty()) {
            return Map.of();
        }
        Map<Long, RoadTerrainSamplingCache.TerrainColumn> terrain = new LinkedHashMap<>(columns.size());
        for (Map.Entry<Long, ColumnSample> entry : columns.entrySet()) {
            ColumnSample sample = entry.getValue();
            if (sample != null) {
                terrain.put(entry.getKey(), new RoadTerrainSamplingCache.TerrainColumn(
                        sample.traversableSurface(),
                        sample.surfaceState(),
                        sample.water()
                ));
            }
        }
        return Map.copyOf(terrain);
    }

    public record ColumnSample(BlockPos traversableSurface,
                               BlockState surfaceState,
                               int terrainPenalty,
                               boolean water,
                               int oceanFloorY) {
        public ColumnSample {
            traversableSurface = traversableSurface == null ? null : traversableSurface.immutable();
            terrainPenalty = Math.max(0, terrainPenalty);
        }
    }
}
