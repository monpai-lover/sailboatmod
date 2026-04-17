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
    private static final int DENSE_ROUTE_RIBBON_RADIUS = 1;

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
                sampleColumn(cache, columns, x, z, blockedColumns, excludedColumns);
            }
        }
        sampleDenseRouteRibbon(cache, columns, start, end, blockedColumns, excludedColumns);

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

    private static void sampleDenseRouteRibbon(RoadTerrainSamplingCache cache,
                                               Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                               BlockPos start,
                                               BlockPos end,
                                               Set<Long> blockedColumns,
                                               Set<Long> excludedColumns) {
        if (cache == null || columns == null || start == null || end == null) {
            return;
        }
        int dx = end.getX() - start.getX();
        int dz = end.getZ() - start.getZ();
        int steps = Math.max(Math.abs(dx), Math.abs(dz));
        if (steps <= 0) {
            sampleColumn(cache, columns, start.getX(), start.getZ(), blockedColumns, excludedColumns);
            return;
        }

        int perpendicularX = Integer.compare(-dz, 0);
        int perpendicularZ = Integer.compare(dx, 0);
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            int sampleX = (int) Math.round(start.getX() + (dx * t));
            int sampleZ = (int) Math.round(start.getZ() + (dz * t));
            sampleColumn(cache, columns, sampleX, sampleZ, blockedColumns, excludedColumns);
            for (int offset = 1; offset <= DENSE_ROUTE_RIBBON_RADIUS; offset++) {
                sampleColumn(
                        cache,
                        columns,
                        sampleX + (perpendicularX * offset),
                        sampleZ + (perpendicularZ * offset),
                        blockedColumns,
                        excludedColumns
                );
                sampleColumn(
                        cache,
                        columns,
                        sampleX - (perpendicularX * offset),
                        sampleZ - (perpendicularZ * offset),
                        blockedColumns,
                        excludedColumns
                );
            }
        }
    }

    private static void sampleColumn(RoadTerrainSamplingCache cache,
                                     Map<Long, RoadPlanningSnapshot.ColumnSample> columns,
                                     int x,
                                     int z,
                                     Set<Long> blockedColumns,
                                     Set<Long> excludedColumns) {
        RoadPlanningTaskService.throwIfCancelled();
        long key = BlockPos.asLong(x, 0, z);
        if (columns.containsKey(key)) {
            return;
        }
        RoadTerrainSamplingCache.TerrainColumn terrain = cache.sample(x, z);
        boolean blocked = blockedColumns != null && blockedColumns.contains(key);
        boolean excluded = excludedColumns != null && excludedColumns.contains(key);
        int terrainPenalty = (blocked || excluded ? 2 : 0) + (terrain.water() ? 2 : 0);
        columns.put(key, new RoadPlanningSnapshot.ColumnSample(
                terrain.surfacePos(),
                terrain.surfaceState(),
                terrainPenalty,
                terrain.water(),
                terrain.surfacePos() == null ? Integer.MIN_VALUE : terrain.surfacePos().getY()
        ));
    }
}
