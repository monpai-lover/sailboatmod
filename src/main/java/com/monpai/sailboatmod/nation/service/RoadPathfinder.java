package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadBezierCenterline;
import com.monpai.sailboatmod.construction.RoadRouteNodePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RoadPathfinder {
    private RoadPathfinder() {
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to) {
        return findPath(level, from, to, Set.of());
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to, Set<Long> blockedColumns) {
        BlockPos start = findSurface(level, from.getX(), from.getZ());
        BlockPos end = findSurface(level, to.getX(), to.getZ());
        if (start == null || end == null || isBlockedRoadColumn(level, start, blockedColumns) || isBlockedRoadColumn(level, end, blockedColumns)) {
            return Collections.emptyList();
        }

        RoadRouteNodePlanner.RoutePlan plan = RoadRouteNodePlanner.plan(
                RoadRouteNodePlanner.RouteMap.of(
                        start,
                        end,
                        pos -> sampleRouteColumn(level, pos, blockedColumns)
                )
        );
        if (plan.path().isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockPos> finalized = RoadBezierCenterline.build(
                plan.path(),
                pos -> sampleSurface(level, pos, blockedColumns),
                blockedColumns
        );
        return finalized.isEmpty() ? plan.path() : finalized;
    }

    private static RoadRouteNodePlanner.RouteColumn sampleRouteColumn(Level level, BlockPos pos, Set<Long> blockedColumns) {
        BlockPos surface = findSurface(level, pos.getX(), pos.getZ());
        if (surface == null) {
            return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
        }
        boolean blocked = isBlockedRoadColumn(level, surface, blockedColumns);
        boolean bridge = requiresBridge(level, surface);
        int adjacentWater = adjacentWaterCount(level, surface);
        int terrainPenalty = terrainPenalty(level, surface);
        boolean preferred = isRoad(level, surface) || (!bridge && adjacentWater == 0 && terrainPenalty <= 1);
        return new RoadRouteNodePlanner.RouteColumn(surface, blocked, bridge, adjacentWater, terrainPenalty, preferred);
    }

    private static RoadBezierCenterline.SurfaceSample sampleSurface(Level level, BlockPos pos, Set<Long> blockedColumns) {
        BlockPos surface = findSurface(level, pos.getX(), pos.getZ());
        if (surface == null) {
            return new RoadBezierCenterline.SurfaceSample(null, true, false);
        }
        return new RoadBezierCenterline.SurfaceSample(
                surface,
                isBlockedRoadColumn(level, surface, blockedColumns),
                requiresBridge(level, surface)
        );
    }

    private static BlockPos findSurface(Level level, int x, int z) {
        BlockPos pos = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        for (int i = 0; i < 5; i++) {
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
}
