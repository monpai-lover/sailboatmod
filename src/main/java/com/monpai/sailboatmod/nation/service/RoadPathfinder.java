package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.road.config.RoadConfig;
import com.monpai.sailboatmod.road.construction.bridge.BridgeRangeDetector;
import com.monpai.sailboatmod.road.model.BridgeSpan;
import com.monpai.sailboatmod.road.pathfinding.PathResult;
import com.monpai.sailboatmod.road.pathfinding.Pathfinder;
import com.monpai.sailboatmod.road.pathfinding.PathfinderFactory;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class RoadPathfinder {
    private RoadPathfinder() {}

    public record ColumnDiagnostics(BlockPos surface, boolean blocked, boolean bridgeRequired,
                                    double terrainPenalty, int adjacentWater, boolean preferred) {}

    public record PlannedPathResult(List<BlockPos> path, boolean success, String failureReason) {}

    private static List<BlockPos> doFindPath(ServerLevel level, BlockPos start, BlockPos end) {
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(start, end, cache);
        return result.success() ? result.path() : List.of();
    }

    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos end,
                                          Set<Long> blockedColumns, boolean allowWaterFallback) {
        if (!(level instanceof ServerLevel serverLevel)) return List.of();
        return doFindPath(serverLevel, start, end);
    }

    public static List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos end,
                                          Set<Long> blockedColumns, Set<Long> excludedColumns,
                                          boolean allowWaterFallback, RoadPlanningSnapshot snapshot) {
        if (level == null || start == null || end == null) return List.of();
        return doFindPath(level, start, end);
    }

    public static List<BlockPos> findGroundPath(ServerLevel level, BlockPos start, BlockPos end,
                                                Set<Long> blockedColumns, Set<Long> excludedColumns,
                                                RoadPlanningSnapshot snapshot) {
        if (level == null || start == null || end == null) return List.of();
        return doFindPath(level, start, end);
    }

    public static PlannedPathResult findPathForPlan(ServerLevel level, BlockPos from, BlockPos to,
                                                    Set<Long> blockedColumns, Set<Long> excludedColumns,
                                                    boolean allowWaterFallback, Object planningContext) {
        if (level == null || from == null || to == null) {
            return new PlannedPathResult(List.of(), false, "Null arguments");
        }
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(from, to, cache);
        return new PlannedPathResult(result.path(), result.success(), result.failureReason());
    }

    public static BlockPos findSurfaceForPlanning(ServerLevel level, int x, int z) {
        return level == null ? null : new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos) {
        return describeColumnForAnchorSelection(level, pos, Set.of(), null);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos, Set<Long> blockedColumns) {
        return describeColumnForAnchorSelection(level, pos, blockedColumns, null);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos, Set<Long> blockedColumns, Object planningContext) {
        if (level == null || pos == null) return new ColumnDiagnostics(pos, false, false, 0.0, 0, false);
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        BlockPos surface = new BlockPos(pos.getX(), cache.getHeight(pos.getX(), pos.getZ()), pos.getZ());
        boolean blocked = blockedColumns != null && blockedColumns.contains(((long) pos.getX() << 32) ^ (pos.getZ() & 0xffffffffL));
        boolean bridgeRequired = cache.isWater(pos.getX(), pos.getZ());
        double terrainPenalty = cache.terrainStability(pos.getX(), pos.getZ());
        int adjacentWater = 0;
        if (cache.isWater(pos.getX() - 1, pos.getZ())) adjacentWater++;
        if (cache.isWater(pos.getX() + 1, pos.getZ())) adjacentWater++;
        if (cache.isWater(pos.getX(), pos.getZ() - 1)) adjacentWater++;
        if (cache.isWater(pos.getX(), pos.getZ() + 1)) adjacentWater++;
        boolean preferred = !blocked && !bridgeRequired && terrainPenalty < 3.0;
        return new ColumnDiagnostics(surface, blocked, bridgeRequired, terrainPenalty, adjacentWater, preferred);
    }

    public static List<BlockPos> collectBridgeDeckAnchors(ServerLevel level, BlockPos start, BlockPos end, Set<Long> blockedColumns, Object planningContext) {
        if (level == null || start == null || end == null) return List.of();
        RoadConfig config = new RoadConfig();
        TerrainSamplingCache cache = new TerrainSamplingCache(level, config.getPathfinding().getSamplingPrecision());
        Pathfinder pathfinder = PathfinderFactory.create(config.getPathfinding());
        PathResult result = pathfinder.findPath(start, end, cache);
        if (!result.success()) return List.of();
        BridgeRangeDetector detector = new BridgeRangeDetector(config.getBridge());
        List<BridgeSpan> spans = detector.detect(result.path(), cache);
        List<BlockPos> anchors = new ArrayList<>();
        for (BridgeSpan span : spans) {
            if (span.startIndex() < result.path().size()) anchors.add(result.path().get(span.startIndex()));
            if (span.endIndex() < result.path().size()) anchors.add(result.path().get(span.endIndex()));
        }
        return anchors;
    }
}
