package com.monpai.sailboatmod.nation.service;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.construction.RoadBezierCenterline;
import com.monpai.sailboatmod.construction.RoadRouteNodePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RoadPathfinder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadPathfinder() {
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to) {
        return findPath(level, from, to, Set.of(), false);
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to, Set<Long> blockedColumns) {
        return findPath(level, from, to, blockedColumns, false);
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to, Set<Long> blockedColumns, boolean allowWaterFallback) {
        ColumnDiagnostics startDiagnostics = describeColumn(level, from, blockedColumns, allowWaterFallback);
        ColumnDiagnostics endDiagnostics = describeColumn(level, to, blockedColumns, allowWaterFallback);
        if (startDiagnostics.surface() == null
                || endDiagnostics.surface() == null
                || startDiagnostics.blocked()
                || endDiagnostics.blocked()) {
            logPathFailure("anchor_rejected", from, to, allowWaterFallback, blockedColumns, startDiagnostics, endDiagnostics, null);
            return Collections.emptyList();
        }

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(
                RoadRouteNodePlanner.RouteMap.of(
                        startDiagnostics.surface(),
                        endDiagnostics.surface(),
                        pos -> sampleRouteColumn(level, pos, blockedColumns, allowWaterFallback)
                )
        );
        if (plan.path().isEmpty()) {
            logPathFailure("planner_empty_path", from, to, allowWaterFallback, blockedColumns, startDiagnostics, endDiagnostics, plan);
            return Collections.emptyList();
        }

        List<BlockPos> finalized = RoadBezierCenterline.build(
                plan.path(),
                pos -> sampleSurface(level, pos, blockedColumns, allowWaterFallback),
                blockedColumns
        );
        if (finalized.isEmpty()) {
            logPathFailure("centerline_fallback_to_route_nodes", from, to, allowWaterFallback, blockedColumns, startDiagnostics, endDiagnostics, plan);
        }
        return finalized.isEmpty() ? plan.path() : finalized;
    }

    private static RoadRouteNodePlanner.RouteColumn sampleRouteColumn(Level level, BlockPos pos, Set<Long> blockedColumns, boolean allowWaterFallback) {
        BlockPos surface = findSurface(level, pos.getX(), pos.getZ());
        if (surface == null) {
            return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
        }
        boolean bridge = requiresBridge(level, surface);
        boolean blocked = isBlockedRoadColumn(level, surface, blockedColumns) || isBridgeBlockedForMode(bridge, allowWaterFallback);
        int adjacentWater = adjacentWaterCount(level, surface);
        int terrainPenalty = terrainPenalty(level, surface);
        boolean preferred = isRoad(level, surface) || (!bridge && adjacentWater == 0 && terrainPenalty <= 1);
        return new RoadRouteNodePlanner.RouteColumn(surface, blocked, bridge, adjacentWater, terrainPenalty, preferred);
    }

    private static RoadBezierCenterline.SurfaceSample sampleSurface(Level level, BlockPos pos, Set<Long> blockedColumns, boolean allowWaterFallback) {
        BlockPos surface = findSurface(level, pos.getX(), pos.getZ());
        if (surface == null) {
            return new RoadBezierCenterline.SurfaceSample(null, true, false, 0);
        }
        boolean bridge = requiresBridge(level, surface);
        return new RoadBezierCenterline.SurfaceSample(
                surface,
                isBlockedRoadColumn(level, surface, blockedColumns) || isBridgeBlockedForMode(bridge, allowWaterFallback),
                bridge,
                adjacentWaterCount(level, surface)
        );
    }

    static boolean isBridgeBlockedForModeForTest(boolean bridge, boolean allowWaterFallback) {
        return isBridgeBlockedForMode(bridge, allowWaterFallback);
    }

    static BlockPos findSurfaceForTest(Level level, int x, int z) {
        return findSurface(level, x, z);
    }

    static ColumnDiagnostics describeColumnForTest(Level level, BlockPos pos, boolean allowWaterFallback) {
        return describeColumn(level, pos, Set.of(), allowWaterFallback);
    }

    static ColumnDiagnostics describeColumnForAnchorSelection(Level level, BlockPos pos) {
        return describeColumn(level, pos, Set.of(), true);
    }

    private static boolean isBridgeBlockedForMode(boolean bridge, boolean allowWaterFallback) {
        return bridge && !allowWaterFallback;
    }

    private static ColumnDiagnostics describeColumn(Level level, BlockPos pos, Set<Long> blockedColumns, boolean allowWaterFallback) {
        BlockPos surface = pos == null ? null : findSurface(level, pos.getX(), pos.getZ());
        if (surface == null) {
            return new ColumnDiagnostics(pos, null, false, false, false, false, true, false, false, 0, 0, false, "surface_missing");
        }

        boolean bridgeRequired = requiresBridge(level, surface);
        boolean blockedByPlanner = blockedColumns.contains(columnKey(surface.getX(), surface.getZ()));
        BlockPos roadPos = surface.above();
        BlockState roadState = level.getBlockState(roadPos);
        boolean roadOccupantSolid = !roadState.isAir() && !roadState.canBeReplaced() && !roadState.liquid() && !isRoad(level, surface);
        BlockState headState = level.getBlockState(roadPos.above());
        boolean headBlocked = !headState.isAir() && !headState.canBeReplaced() && !headState.liquid();
        boolean bridgeBlockedByMode = isBridgeBlockedForMode(bridgeRequired, allowWaterFallback);
        int adjacentWater = adjacentWaterCount(level, surface);
        int terrainPenalty = terrainPenalty(level, surface);
        boolean preferred = isRoad(level, surface) || (!bridgeRequired && adjacentWater == 0 && terrainPenalty <= 1);
        String reason = blockingReason(surface != null, blockedByPlanner, roadOccupantSolid, headBlocked, bridgeBlockedByMode);
        return new ColumnDiagnostics(
                pos,
                surface,
                blockedByPlanner || roadOccupantSolid || headBlocked || bridgeBlockedByMode,
                blockedByPlanner,
                roadOccupantSolid,
                headBlocked,
                false,
                bridgeRequired,
                bridgeBlockedByMode,
                adjacentWater,
                terrainPenalty,
                preferred,
                reason
        );
    }

    private static String blockingReason(boolean hasSurface,
                                         boolean blockedByPlanner,
                                         boolean blockedByOccupant,
                                         boolean blockedByHeadroom,
                                         boolean blockedByBridgeMode) {
        if (!hasSurface) {
            return "surface_missing";
        }
        if (blockedByPlanner) {
            return "blocked_column";
        }
        if (blockedByOccupant) {
            return "occupied_road_space";
        }
        if (blockedByHeadroom) {
            return "blocked_headroom";
        }
        if (blockedByBridgeMode) {
            return "bridge_disallowed";
        }
        return "clear";
    }

    private static void logPathFailure(String stage,
                                       BlockPos requestedFrom,
                                       BlockPos requestedTo,
                                       boolean allowWaterFallback,
                                       Set<Long> blockedColumns,
                                       ColumnDiagnostics start,
                                       ColumnDiagnostics end,
                                       RoadRouteNodePlanner.RoutePlan plan) {
        LOGGER.warn(
                "RoadPathfinder {} requestedFrom={} requestedTo={} allowWaterFallback={} blockedColumns={} start={} end={} planPathSize={} planUsedBridge={} planBridgeColumns={} planLongestBridgeRun={}",
                stage,
                requestedFrom,
                requestedTo,
                allowWaterFallback,
                blockedColumns == null ? -1 : blockedColumns.size(),
                start,
                end,
                plan == null ? -1 : plan.path().size(),
                plan != null && plan.usedBridge(),
                plan == null ? -1 : plan.totalBridgeColumns(),
                plan == null ? -1 : plan.longestBridgeRun()
        );
    }

    private static BlockPos findSurface(Level level, int x, int z) {
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        while (pos.getY() >= level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.liquid() && state.isFaceSturdy(level, pos, Direction.UP)) {
                return pos;
            }
            pos = pos.below();
        }
        return null;
    }

    private static boolean requiresBridge(Level level, BlockPos pos) {
        return crossesWater(level, pos);
    }

    private static boolean isBlockedRoadColumn(Level level, BlockPos surfacePos, Set<Long> blockedColumns) {
        if (surfacePos == null) {
            return true;
        }
        if (blockedColumns.contains(columnKey(surfacePos.getX(), surfacePos.getZ()))) {
            return true;
        }
        BlockPos roadPos = surfacePos.above();
        BlockState roadState = level.getBlockState(roadPos);
        if (!roadState.isAir() && !roadState.canBeReplaced() && !roadState.liquid() && !isRoad(level, surfacePos)) {
            return true;
        }
        BlockState headState = level.getBlockState(roadPos.above());
        return !headState.isAir() && !headState.canBeReplaced() && !headState.liquid();
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static boolean crossesWater(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getFluidState().isEmpty()
                || !level.getBlockState(pos.above()).getFluidState().isEmpty()
                || !level.getBlockState(pos.below()).getFluidState().isEmpty();
    }

    private static int adjacentWaterCount(Level level, BlockPos pos) {
        int count = 0;
        for (BlockPos nearby : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            if (!level.getBlockState(nearby).getFluidState().isEmpty()
                    || !level.getBlockState(nearby.below()).getFluidState().isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isSoftGround(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY);
    }

    private static boolean isRoad(Level level, BlockPos pos) {
        return level.getBlockState(pos.above()).is(Blocks.STONE_BRICK_SLAB);
    }

    private static int terrainPenalty(Level level, BlockPos pos) {
        int penalty = isSoftGround(level, pos) ? 2 : 0;
        int maxNeighborDiff = 0;
        for (BlockPos nearby : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            BlockPos neighborSurface = findSurface(level, nearby.getX(), nearby.getZ());
            if (neighborSurface != null) {
                maxNeighborDiff = Math.max(maxNeighborDiff, Math.abs(neighborSurface.getY() - pos.getY()));
            }
        }
        if (maxNeighborDiff >= 3) {
            penalty += maxNeighborDiff * 2;
        } else {
            penalty += maxNeighborDiff;
        }
        return penalty;
    }

    record ColumnDiagnostics(BlockPos requested,
                             BlockPos surface,
                             boolean blocked,
                             boolean blockedByPlanner,
                             boolean blockedByOccupant,
                             boolean blockedByHeadroom,
                             boolean missingSurface,
                             boolean bridgeRequired,
                             boolean bridgeBlockedByMode,
                             int adjacentWater,
                             int terrainPenalty,
                             boolean preferred,
                             String reason) {
    }
}
