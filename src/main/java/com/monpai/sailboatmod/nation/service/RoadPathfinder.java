package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import java.util.List;
import java.util.Set;

/**
 * Stub class - road system refactored. Pending integration with new road package.
 */
public final class RoadPathfinder {
    private RoadPathfinder() {}

    public record ColumnDiagnostics(BlockPos surface, boolean blocked, boolean bridgeRequired,
                                    double terrainPenalty, int adjacentWater, boolean preferred) {}

    public record PlannedPathResult(List<BlockPos> path, boolean success, String failureReason) {}

    public static List<BlockPos> findPath(Level level, BlockPos start, BlockPos end,
                                          Set<Long> blockedColumns, boolean allowWaterFallback) {
        return List.of();
    }

    public static List<BlockPos> findPath(ServerLevel level, BlockPos start, BlockPos end,
                                          Set<Long> blockedColumns, Set<Long> excludedColumns,
                                          boolean allowWaterFallback, RoadPlanningSnapshot snapshot) {
        return List.of();
    }

    public static List<BlockPos> findGroundPath(ServerLevel level, BlockPos start, BlockPos end,
                                                Set<Long> blockedColumns, Set<Long> excludedColumns,
                                                RoadPlanningSnapshot snapshot) {
        return List.of();
    }

    public static PlannedPathResult findPathForPlan(ServerLevel level, BlockPos from, BlockPos to,
                                                    Set<Long> blockedColumns, Set<Long> excludedColumns,
                                                    boolean allowWaterFallback, Object planningContext) {
        return new PlannedPathResult(List.of(), false, "Road system refactored");
    }

    public static BlockPos findSurfaceForPlanning(ServerLevel level, int x, int z) {
        return level == null ? null : new BlockPos(x, level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE, x, z) - 1, z);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos) {
        return new ColumnDiagnostics(pos, false, false, 0.0, 0, true);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos, Set<Long> blockedColumns) {
        return new ColumnDiagnostics(pos, false, false, 0.0, 0, true);
    }

    public static ColumnDiagnostics describeColumnForAnchorSelection(ServerLevel level, BlockPos pos, Set<Long> blockedColumns, Object planningContext) {
        return new ColumnDiagnostics(pos, false, false, 0.0, 0, true);
    }

    public static List<BlockPos> collectBridgeDeckAnchors(ServerLevel level, BlockPos start, BlockPos end, Set<Long> blockedColumns, Object planningContext) {
        return List.of();
    }
}
