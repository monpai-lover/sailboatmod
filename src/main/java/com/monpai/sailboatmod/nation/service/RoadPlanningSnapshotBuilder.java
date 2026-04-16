package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class RoadPlanningSnapshotBuilder {
    private static final int CORRIDOR_PADDING = 8;
    private static final int SAMPLE_STEP = 2;

    private RoadPlanningSnapshotBuilder() {
    }

    public static RoadPlanningSnapshot build(ServerLevel level,
                                             BlockPos start,
                                             BlockPos end,
                                             Set<Long> blockedColumns,
                                             Set<Long> excludedColumns) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");

        RoadTerrainSamplingCache cache = new RoadTerrainSamplingCache(level);
        LinkedHashMap<Long, RoadPlanningSnapshot.ColumnSample> columns = new LinkedHashMap<>();
        int minX = Math.min(start.getX(), end.getX()) - CORRIDOR_PADDING;
        int maxX = Math.max(start.getX(), end.getX()) + CORRIDOR_PADDING;
        int minZ = Math.min(start.getZ(), end.getZ()) - CORRIDOR_PADDING;
        int maxZ = Math.max(start.getZ(), end.getZ()) + CORRIDOR_PADDING;

        for (int x = minX; x <= maxX; x += SAMPLE_STEP) {
            RoadPlanningTaskService.throwIfCancelled();
            for (int z = minZ; z <= maxZ; z += SAMPLE_STEP) {
                RoadPlanningTaskService.throwIfCancelled();
                RoadTerrainSamplingCache.TerrainColumn terrain = cache.sample(x, z);
                boolean blocked = blockedColumns != null && blockedColumns.contains(BlockPos.asLong(x, 0, z));
                boolean excluded = excludedColumns != null && excludedColumns.contains(BlockPos.asLong(x, 0, z));
                int terrainPenalty = (blocked || excluded ? 2 : 0) + (terrain.water() ? 2 : 0);
                columns.put(BlockPos.asLong(x, 0, z), new RoadPlanningSnapshot.ColumnSample(
                        terrain.surfacePos(),
                        terrain.surfaceState(),
                        terrainPenalty,
                        terrain.water(),
                        terrain.surfacePos() == null ? Integer.MIN_VALUE : terrain.surfacePos().getY()
                ));
            }
        }

        RoadPlanningIslandClassifier.IslandSummary targetIslandSummary = RoadPlanningIslandClassifier.classify(
                columns,
                end,
                start,
                SAMPLE_STEP
        );
        RoadPlanningIslandClassifier.IslandSummary sourceIslandSummary = RoadPlanningIslandClassifier.classify(
                columns,
                start,
                end,
                SAMPLE_STEP
        );

        return new RoadPlanningSnapshot(
                Map.copyOf(columns),
                sourceIslandSummary.isIslandLike(),
                targetIslandSummary.isIslandLike(),
                java.util.List.of(start.immutable(), end.immutable()),
                start,
                end
        );
    }

    static RoadPlanningSnapshot buildForTest(ServerLevel level,
                                             BlockPos start,
                                             BlockPos end,
                                             Set<Long> blockedColumns,
                                             Set<Long> excludedColumns) {
        return build(level, start, end, blockedColumns, excludedColumns);
    }
}
