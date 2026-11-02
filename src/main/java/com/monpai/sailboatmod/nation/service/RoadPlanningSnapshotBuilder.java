package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RoadPlanningSnapshotBuilder {
    private RoadPlanningSnapshotBuilder() {}

    public static RoadPlanningSnapshot build(ServerLevel level, BlockPos start, BlockPos end,
                                             Set<Long> blockedColumns, Set<Long> excludedColumns) {
        if (level == null || start == null || end == null) {
            return new RoadPlanningSnapshot(Map.of(), false, false, List.of(), start, end);
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());

        Map<Long, RoadPlanningSnapshot.ColumnSample> columns = new HashMap<>();
        int minX = Math.min(start.getX(), end.getX()) - 8;
        int maxX = Math.max(start.getX(), end.getX()) + 8;
        int minZ = Math.min(start.getZ(), end.getZ()) - 8;
        int maxZ = Math.max(start.getZ(), end.getZ()) + 8;

        int step = Math.max(1, (int) Math.sqrt(Math.max(maxX - minX, maxZ - minZ) / 4.0));
        for (int x = minX; x <= maxX; x += step) {
            for (int z = minZ; z <= maxZ; z += step) {
                long key = ((long) x << 32) ^ (z & 0xffffffffL);
                int surfaceY = cache.getHeight(x, z);
                boolean water = cache.isWater(x, z);
                columns.put(key, new RoadPlanningSnapshot.ColumnSample(surfaceY, water));
            }
        }

        boolean sourceIsland = cache.isNearWater(start.getX(), start.getZ());
        boolean targetIsland = cache.isNearWater(end.getX(), end.getZ());

        List<BlockPos> bridgeAnchors = RoadPathfinder.collectBridgeDeckAnchors(level, start, end, blockedColumns, null);

        return new RoadPlanningSnapshot(columns, sourceIsland, targetIsland, bridgeAnchors, start, end);
    }
}
