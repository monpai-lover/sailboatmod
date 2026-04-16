package com.monpai.sailboatmod.nation.service;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.construction.RoadBezierCenterline;
import com.monpai.sailboatmod.construction.RoadBridgePierPlanner;
import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.construction.RoadRouteNodePlanner;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class RoadPathfinder {
    private static final Logger LOGGER = LogUtils.getLogger();

    private RoadPathfinder() {
    }

    public record PlannedPathResult(List<BlockPos> path, RoadPlanningFailureReason failureReason) {
        public PlannedPathResult {
            path = path == null ? List.of() : List.copyOf(path);
            failureReason = failureReason == null ? RoadPlanningFailureReason.NONE : failureReason;
        }

        public boolean success() {
            return path.size() >= 2;
        }
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to) {
        return findPath(level, from, to, Set.of(), false);
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to, Set<Long> blockedColumns) {
        return findPath(level, from, to, blockedColumns, false);
    }

    public static List<BlockPos> findPath(Level level, BlockPos from, BlockPos to, Set<Long> blockedColumns, boolean allowWaterFallback) {
        return findPath(level, from, to, blockedColumns, Set.of(), allowWaterFallback);
    }

    public static List<BlockPos> findPath(Level level,
                                          BlockPos from,
                                          BlockPos to,
                                          Set<Long> blockedColumns,
                                          Set<Long> excludedColumns,
                                          boolean allowWaterFallback) {
        return findPath(level, from, to, blockedColumns, excludedColumns, allowWaterFallback, (RoadPlanningPassContext) null);
    }

    public static List<BlockPos> findPath(Level level,
                                          BlockPos from,
                                          BlockPos to,
                                          Set<Long> blockedColumns,
                                          Set<Long> excludedColumns,
                                          boolean allowWaterFallback,
                                          RoadPlanningSnapshot snapshot) {
        return findPath(level, from, to, blockedColumns, excludedColumns, allowWaterFallback, passContext(level, snapshot));
    }

    static List<BlockPos> findPath(Level level,
                                   BlockPos from,
                                   BlockPos to,
                                   Set<Long> blockedColumns,
                                   Set<Long> excludedColumns,
                                   boolean allowWaterFallback,
                                   RoadPlanningPassContext context) {
        return findPathForPlan(level, from, to, blockedColumns, excludedColumns, allowWaterFallback, context).path();
    }

    static PlannedPathResult findPathForPlan(Level level,
                                             BlockPos from,
                                             BlockPos to,
                                             Set<Long> blockedColumns,
                                             Set<Long> excludedColumns,
                                             boolean allowWaterFallback,
                                             RoadPlanningPassContext context) {
        if (from != null && from.equals(to)) {
            return new PlannedPathResult(List.of(from.immutable()), RoadPlanningFailureReason.NONE);
        }
        Set<Long> combinedBlocked = unblockColumns(
                mergeBlockedColumns(blockedColumns, excludedColumns),
                from,
                to
        );
        ColumnDiagnostics startDiagnostics = describeColumn(level, from, combinedBlocked, allowWaterFallback, context);
        ColumnDiagnostics endDiagnostics = describeColumn(level, to, combinedBlocked, allowWaterFallback, context);
        if (startDiagnostics.surface() == null
                || endDiagnostics.surface() == null
                || startDiagnostics.blocked()
                || endDiagnostics.blocked()) {
            logPathFailure("anchor_rejected", from, to, allowWaterFallback, combinedBlocked, startDiagnostics, endDiagnostics, null);
            return new PlannedPathResult(List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }

        RoadRouteNodePlanner.RouteMap routeMap = RoadRouteNodePlanner.RouteMap.of(
                startDiagnostics.surface(),
                endDiagnostics.surface(),
                pos -> sampleRouteColumn(level, pos, combinedBlocked, allowWaterFallback, context)
        );
        RoadRouteNodePlanner.RoutePlan plan = allowWaterFallback
                ? RoadRouteNodePlanner.planWithBridgePiers(
                        routeMap,
                        buildBridgePierNodes(level, startDiagnostics.surface(), endDiagnostics.surface(), combinedBlocked, context)
                )
                : RoadRouteNodePlanner.plan(routeMap);
        if (plan.path().isEmpty()) {
            logPathFailure("planner_empty_path", from, to, allowWaterFallback, combinedBlocked, startDiagnostics, endDiagnostics, plan);
            return new PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
        }

        List<BlockPos> finalized = RoadBezierCenterline.build(
                plan.path(),
                pos -> sampleSurface(level, pos, combinedBlocked, allowWaterFallback, context),
                combinedBlocked
        );
        if (finalized.isEmpty()) {
            logPathFailure("centerline_fallback_to_route_nodes", from, to, allowWaterFallback, combinedBlocked, startDiagnostics, endDiagnostics, plan);
        }
        List<BlockPos> normalizedFinalized = normalizeReturnedPath(from, to, finalized);
        if (!normalizedFinalized.isEmpty()) {
            return new PlannedPathResult(normalizedFinalized, RoadPlanningFailureReason.NONE);
        }
        List<BlockPos> normalizedPlanned = normalizeReturnedPath(from, to, plan.path());
        if (normalizedPlanned.isEmpty()) {
            logPathFailure("normalized_path_invalid", from, to, allowWaterFallback, combinedBlocked, startDiagnostics, endDiagnostics, plan);
            return new PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
        }
        return new PlannedPathResult(normalizedPlanned, RoadPlanningFailureReason.NONE);
    }

    static PlannedPathResult findGroundPathForPlan(Level level,
                                                   BlockPos from,
                                                   BlockPos to,
                                                   Set<Long> blockedColumns,
                                                   Set<Long> excludedColumns) {
        return findGroundPathForPlan(level, from, to, blockedColumns, excludedColumns, (RoadPlanningPassContext) null);
    }

    static PlannedPathResult findGroundPathForPlan(Level level,
                                                   BlockPos from,
                                                   BlockPos to,
                                                   Set<Long> blockedColumns,
                                                   Set<Long> excludedColumns,
                                                   RoadPlanningSnapshot snapshot) {
        return findGroundPathForPlan(level, from, to, blockedColumns, excludedColumns, passContext(level, snapshot));
    }

    static PlannedPathResult findGroundPathForPlan(Level level,
                                                   BlockPos from,
                                                   BlockPos to,
                                                   Set<Long> blockedColumns,
                                                   Set<Long> excludedColumns,
                                                   RoadPlanningPassContext context) {
        return findPathForPlan(level, from, to, blockedColumns, excludedColumns, false, context);
    }

    private static RoadRouteNodePlanner.RouteColumn sampleRouteColumn(Level level,
                                                                      BlockPos pos,
                                                                      Set<Long> blockedColumns,
                                                                      boolean allowWaterFallback,
                                                                      RoadPlanningPassContext context) {
        BlockPos terrainSurface = findSurface(level, pos.getX(), pos.getZ(), context);
        if (terrainSurface == null) {
            return new RoadRouteNodePlanner.RouteColumn(null, true, false, 0, 0, false);
        }
        BlockPos traversableSurface = resolveTraversableSurface(level, pos, terrainSurface, allowWaterFallback);
        boolean bridge = requiresBridge(level, terrainSurface);
        boolean blocked = isBlockedRoadColumn(level, traversableSurface, blockedColumns) || isBridgeBlockedForMode(bridge, allowWaterFallback);
        int adjacentWater = adjacentWaterCount(level, terrainSurface, context);
        int terrainPenalty = terrainPenalty(level, terrainSurface, context);
        boolean preferred = isRoad(level, traversableSurface) || (!bridge && adjacentWater == 0 && terrainPenalty <= 1);
        return new RoadRouteNodePlanner.RouteColumn(traversableSurface, blocked, bridge, adjacentWater, terrainPenalty, preferred);
    }

    private static RoadBezierCenterline.SurfaceSample sampleSurface(Level level,
                                                                    BlockPos pos,
                                                                    Set<Long> blockedColumns,
                                                                    boolean allowWaterFallback,
                                                                    RoadPlanningPassContext context) {
        BlockPos terrainSurface = findSurface(level, pos.getX(), pos.getZ(), context);
        if (terrainSurface == null) {
            return new RoadBezierCenterline.SurfaceSample(null, true, false, 0);
        }
        BlockPos traversableSurface = resolveTraversableSurface(level, pos, terrainSurface, allowWaterFallback);
        boolean bridge = requiresBridge(level, terrainSurface);
        return new RoadBezierCenterline.SurfaceSample(
                traversableSurface,
                isBlockedRoadColumn(level, traversableSurface, blockedColumns) || isBridgeBlockedForMode(bridge, allowWaterFallback),
                bridge,
                adjacentWaterCount(level, terrainSurface, context)
        );
    }

    static boolean isBridgeBlockedForModeForTest(boolean bridge, boolean allowWaterFallback) {
        return isBridgeBlockedForMode(bridge, allowWaterFallback);
    }

    static BlockPos findSurfaceForTest(Level level, int x, int z) {
        return findSurface(level, x, z, null);
    }

    static ColumnDiagnostics describeColumnForTest(Level level, BlockPos pos, boolean allowWaterFallback) {
        return describeColumn(level, pos, Set.of(), allowWaterFallback, null);
    }

    static ColumnDiagnostics describeColumnForAnchorSelection(Level level, BlockPos pos) {
        return describeColumnForAnchorSelection(level, pos, Set.of(), null);
    }

    static ColumnDiagnostics describeColumnForAnchorSelection(Level level, BlockPos pos, Set<Long> blockedColumns) {
        return describeColumnForAnchorSelection(level, pos, blockedColumns, null);
    }

    static ColumnDiagnostics describeColumnForAnchorSelection(Level level,
                                                             BlockPos pos,
                                                             Set<Long> blockedColumns,
                                                             RoadPlanningPassContext context) {
        return describeColumn(level, pos, blockedColumns == null ? Set.of() : blockedColumns, true, context);
    }

    static List<BlockPos> normalizeReturnedPathForTest(BlockPos from, BlockPos to, List<BlockPos> path) {
        return normalizeReturnedPath(from, to, path);
    }

    private static boolean isBridgeBlockedForMode(boolean bridge, boolean allowWaterFallback) {
        return bridge && !allowWaterFallback;
    }

    private static List<BlockPos> normalizeReturnedPath(BlockPos from, BlockPos to, List<BlockPos> path) {
        if (from == null || to == null || path == null) {
            return List.of();
        }
        if (from.equals(to)) {
            return List.of(from.immutable());
        }
        if (path.isEmpty()) {
            return List.of();
        }
        List<BlockPos> normalized = new ArrayList<>();
        appendPathNode(normalized, from);
        for (BlockPos pos : path) {
            appendPathNode(normalized, pos);
        }
        appendPathNode(normalized, to);
        return isContinuousPath(normalized) ? List.copyOf(normalized) : List.of();
    }

    private static boolean isContinuousPath(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return false;
        }
        for (int i = 1; i < path.size(); i++) {
            BlockPos previous = path.get(i - 1);
            BlockPos current = path.get(i);
            if (previous == null || current == null) {
                return false;
            }
            int dx = Math.abs(current.getX() - previous.getX());
            int dz = Math.abs(current.getZ() - previous.getZ());
            if (Math.max(dx, dz) > 1) {
                return false;
            }
        }
        return true;
    }

    private static void appendPathNode(List<BlockPos> ordered, BlockPos pos) {
        if (ordered == null || pos == null) {
            return;
        }
        BlockPos immutable = pos.immutable();
        if (!ordered.isEmpty() && ordered.get(ordered.size() - 1).equals(immutable)) {
            return;
        }
        ordered.add(immutable);
    }

    private static ColumnDiagnostics describeColumn(Level level,
                                                    BlockPos pos,
                                                    Set<Long> blockedColumns,
                                                    boolean allowWaterFallback,
                                                    RoadPlanningPassContext context) {
        BlockPos terrainSurface = pos == null ? null : findSurface(level, pos.getX(), pos.getZ(), context);
        if (terrainSurface == null) {
            return new ColumnDiagnostics(pos, null, false, false, false, false, true, false, false, 0, 0, false, "surface_missing");
        }

        BlockPos traversableSurface = resolveTraversableSurface(level, pos, terrainSurface, allowWaterFallback);
        boolean bridgeRequired = requiresBridge(level, terrainSurface);
        boolean blockedByPlanner = blockedColumns.contains(columnKey(terrainSurface.getX(), terrainSurface.getZ()));
        BlockPos occupancySurface = traversableSurface == null ? terrainSurface : traversableSurface;
        BlockPos roadPos = occupancySurface.above();
        BlockState roadState = level.getBlockState(roadPos);
        boolean roadOccupantSolid = !isPassableRoadSpace(roadState) && !isRoad(level, occupancySurface);
        BlockState headState = level.getBlockState(roadPos.above());
        boolean headBlocked = !isPassableRoadSpace(headState);
        boolean bridgeBlockedByMode = isBridgeBlockedForMode(bridgeRequired, allowWaterFallback);
        int adjacentWater = adjacentWaterCount(level, terrainSurface, context);
        int terrainPenalty = terrainPenalty(level, terrainSurface, context);
        boolean preferred = isRoad(level, occupancySurface) || (!bridgeRequired && adjacentWater == 0 && terrainPenalty <= 1);
        String reason = blockingReason(traversableSurface != null, blockedByPlanner, roadOccupantSolid, headBlocked, bridgeBlockedByMode);
        return new ColumnDiagnostics(
                pos,
                traversableSurface,
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

    private static BlockPos resolveTraversableSurface(Level level,
                                                      BlockPos requestedPos,
                                                      BlockPos terrainSurface,
                                                      boolean allowWaterFallback) {
        if (terrainSurface == null) {
            return null;
        }
        if (shouldPreserveRaisedBridgeAnchor(level, requestedPos, terrainSurface, allowWaterFallback)) {
            return new BlockPos(terrainSurface.getX(), requestedPos.getY(), terrainSurface.getZ());
        }
        return terrainSurface;
    }

    private static boolean shouldPreserveRaisedBridgeAnchor(Level level,
                                                            BlockPos requestedPos,
                                                            BlockPos terrainSurface,
                                                            boolean allowWaterFallback) {
        return level != null
                && requestedPos != null
                && terrainSurface != null
                && allowWaterFallback
                && requestedPos.getY() > terrainSurface.getY()
                && (hasFluidColumnBetween(level, terrainSurface, requestedPos)
                || hasClearRaisedAnchorColumn(level, terrainSurface, requestedPos)
                || hasExistingRaisedRoadDeck(level, requestedPos));
    }

    private static boolean hasExistingRaisedRoadDeck(Level level, BlockPos requestedPos) {
        if (level == null || requestedPos == null) {
            return false;
        }
        BlockState roadState = level.getBlockState(requestedPos.above());
        BlockState headState = level.getBlockState(requestedPos.above(2));
        return isKnownRoadSurface(roadState) && isPassableRoadSpace(headState);
    }

    private static boolean hasFluidColumnBetween(Level level, BlockPos lower, BlockPos upper) {
        if (level == null || lower == null || upper == null || upper.getY() <= lower.getY()) {
            return false;
        }
        for (int y = lower.getY() + 1; y < upper.getY(); y++) {
            if (isFluidColumn(level.getBlockState(new BlockPos(lower.getX(), y, lower.getZ())))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasClearRaisedAnchorColumn(Level level, BlockPos lower, BlockPos upper) {
        if (level == null || lower == null || upper == null || upper.getY() <= lower.getY()) {
            return false;
        }
        for (int y = lower.getY() + 1; y <= upper.getY(); y++) {
            BlockState state = level.getBlockState(new BlockPos(lower.getX(), y, lower.getZ()));
            if (!isPassableRoadSpace(state)) {
                return false;
            }
        }
        return true;
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

    private static BlockPos findSurface(Level level, int x, int z, RoadPlanningPassContext context) {
        if (context != null) {
            return context.resolveRoadSurface(
                    x,
                    z,
                    (sampleX, sampleZ) -> computeSurface(level, context.sampleColumn(sampleX, sampleZ).surfacePos())
            );
        }
        return computeSurface(level, level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z)).below());
    }

    private static BlockPos computeSurface(Level level, BlockPos startPos) {
        BlockPos pos = startPos;
        if (pos == null) {
            return null;
        }
        while (pos.getY() >= level.getMinBuildHeight()) {
            BlockState state = level.getBlockState(pos);
            if (isTerrainSurface(level, pos, state)) {
                return pos;
            }
            pos = pos.below();
        }
        return null;
    }

    static BlockPos findSurfaceForPlanning(Level level, int x, int z) {
        return findSurface(level, x, z, null);
    }

    private static boolean isTerrainSurface(Level level, BlockPos pos, BlockState state) {
        return state != null
                && !state.isAir()
                && !state.liquid()
                && state.isFaceSturdy(level, pos, Direction.UP)
                && !isSurfaceObstacle(state);
    }

    private static boolean isSurfaceObstacle(BlockState state) {
        if (state == null) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.CROPS)
                || state.getBlock() instanceof LeavesBlock
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae")
                || path.endsWith("_leaves")
                || state.is(Blocks.VINE)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.LILY_PAD);
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
        if (!isPassableRoadSpace(roadState) && !isRoad(level, surfacePos)) {
            return true;
        }
        BlockState headState = level.getBlockState(roadPos.above());
        return !isPassableRoadSpace(headState);
    }

    private static long columnKey(int x, int z) {
        return RoadCoreExclusion.columnKey(x, z);
    }

    private static Set<Long> mergeBlockedColumns(Set<Long> blockedColumns, Set<Long> excludedColumns) {
        if ((blockedColumns == null || blockedColumns.isEmpty()) && (excludedColumns == null || excludedColumns.isEmpty())) {
            return Set.of();
        }
        LinkedHashMap<Long, Long> merged = new LinkedHashMap<>();
        if (blockedColumns != null) {
            for (Long blocked : blockedColumns) {
                if (blocked != null) {
                    merged.put(blocked, blocked);
                }
            }
        }
        if (excludedColumns != null) {
            for (Long excluded : excludedColumns) {
                if (excluded != null) {
                    merged.put(excluded, excluded);
                }
            }
        }
        return Set.copyOf(merged.keySet());
    }

    private static Set<Long> unblockColumns(Set<Long> blockedColumns, BlockPos... positions) {
        if (blockedColumns == null || blockedColumns.isEmpty()) {
            return Set.of();
        }
        LinkedHashMap<Long, Long> updated = new LinkedHashMap<>();
        for (Long blocked : blockedColumns) {
            if (blocked != null) {
                updated.put(blocked, blocked);
            }
        }
        if (positions != null) {
            for (BlockPos pos : positions) {
                if (pos != null) {
                    updated.remove(columnKey(pos.getX(), pos.getZ()));
                }
            }
        }
        return updated.isEmpty() ? Set.of() : Set.copyOf(updated.keySet());
    }

    private static RoadPlanningPassContext passContext(Level level, RoadPlanningSnapshot snapshot) {
        if (level == null || snapshot == null) {
            return null;
        }
        return new RoadPlanningPassContext(level, snapshot);
    }

    private static List<RoadBridgePierPlanner.PierNode> buildBridgePierNodes(Level level,
                                                                              BlockPos start,
                                                                              BlockPos end,
                                                                              Set<Long> blockedColumns,
                                                                              RoadPlanningPassContext context) {
        if (level == null || start == null || end == null) {
            return List.of();
        }
        int steps = Math.max(Math.abs(end.getX() - start.getX()), Math.abs(end.getZ() - start.getZ()));
        if (steps < 2) {
            return List.of();
        }
        LinkedHashMap<Long, RoadBridgePierPlanner.WaterColumn> candidates = new LinkedHashMap<>();
        double dx = (end.getX() - start.getX()) / (double) steps;
        double dz = (end.getZ() - start.getZ()) / (double) steps;
        for (int i = 0; i <= steps; i++) {
            int x = (int) Math.round(start.getX() + (dx * i));
            int z = (int) Math.round(start.getZ() + (dz * i));
            BlockPos surface = findSurface(level, x, z, context);
            if (surface == null || !requiresBridge(level, surface)) {
                continue;
            }
            int waterSurfaceY = findWaterSurfaceY(level, x, z, context);
            if (waterSurfaceY == Integer.MIN_VALUE) {
                continue;
            }
            long key = columnKey(x, z);
            candidates.putIfAbsent(key, new RoadBridgePierPlanner.WaterColumn(
                    new BlockPos(x, waterSurfaceY, z),
                    waterSurfaceY,
                    surface.getY(),
                    true,
                    blockedColumns != null && blockedColumns.contains(key)
            ));
        }
        if (candidates.isEmpty()) {
            return List.of();
        }
        return RoadBridgePierPlanner.planPierNodes(thinPierCandidates(new ArrayList<>(candidates.values())), 5);
    }

    public static List<BlockPos> collectBridgeDeckAnchors(Level level,
                                                          BlockPos start,
                                                          BlockPos end,
                                                          Set<Long> blockedColumns) {
        return collectBridgeDeckAnchors(level, start, end, blockedColumns, (RoadPlanningPassContext) null);
    }

    public static List<BlockPos> collectBridgeDeckAnchors(Level level,
                                                          BlockPos start,
                                                          BlockPos end,
                                                          Set<Long> blockedColumns,
                                                          RoadPlanningSnapshot snapshot) {
        return collectBridgeDeckAnchors(level, start, end, blockedColumns, passContext(level, snapshot));
    }

    static List<BlockPos> collectBridgeDeckAnchors(Level level,
                                                   BlockPos start,
                                                   BlockPos end,
                                                   Set<Long> blockedColumns,
                                                   RoadPlanningPassContext context) {
        List<RoadBridgePierPlanner.PierNode> pierNodes = buildBridgePierNodes(level, start, end, blockedColumns, context);
        if (pierNodes.isEmpty()) {
            return List.of();
        }
        List<BlockPos> anchors = new ArrayList<>(pierNodes.size());
        for (RoadBridgePierPlanner.PierNode pierNode : pierNodes) {
            if (pierNode == null || pierNode.foundationPos() == null) {
                continue;
            }
            anchors.add(new BlockPos(
                    pierNode.foundationPos().getX(),
                    pierNode.deckY(),
                    pierNode.foundationPos().getZ()
            ));
        }
        return List.copyOf(anchors);
    }

    private static List<RoadBridgePierPlanner.WaterColumn> thinPierCandidates(List<RoadBridgePierPlanner.WaterColumn> columns) {
        if (columns == null || columns.size() <= 24) {
            return columns == null ? List.of() : List.copyOf(columns);
        }
        ArrayList<RoadBridgePierPlanner.WaterColumn> thinned = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            if (i == 0 || i == columns.size() - 1 || i % 2 == 0) {
                thinned.add(columns.get(i));
            }
        }
        return List.copyOf(thinned);
    }

    private static int findWaterSurfaceY(Level level, int x, int z, RoadPlanningPassContext context) {
        BlockPos seed = context == null ? null : context.sampleColumn(x, z).surfacePos();
        BlockPos heightmapSurface = level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
        BlockPos cursor = seed == null
                ? heightmapSurface
                : new BlockPos(x, Math.max(seed.getY(), heightmapSurface.getY()), z);
        while (cursor.getY() >= level.getMinBuildHeight()) {
            if (isFluidColumn(level.getBlockState(cursor))) {
                return cursor.getY();
            }
            cursor = cursor.below();
        }
        return Integer.MIN_VALUE;
    }

    private static boolean crossesWater(Level level, BlockPos pos) {
        return isFluidColumn(level.getBlockState(pos))
                || isFluidColumn(level.getBlockState(pos.above()))
                || isFluidColumn(level.getBlockState(pos.below()));
    }

    private static int adjacentWaterCount(Level level, BlockPos pos, RoadPlanningPassContext context) {
        int count = 0;
        for (BlockPos nearby : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            BlockPos nearbySurface = findSurface(level, nearby.getX(), nearby.getZ(), context);
            if (nearbySurface != null && (isFluidColumn(level.getBlockState(nearbySurface))
                    || isFluidColumn(level.getBlockState(nearbySurface.above()))
                    || isFluidColumn(level.getBlockState(nearbySurface.below())))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isFluidColumn(BlockState state) {
        return state != null && (state.liquid() || !state.getFluidState().isEmpty());
    }

    private static boolean isPassableRoadSpace(BlockState state) {
        return state == null
                || state.isAir()
                || state.canBeReplaced()
                || isFluidColumn(state);
    }

    private static boolean isSoftGround(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.SAND)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.MUD)
                || state.is(Blocks.CLAY);
    }

    private static boolean isRoad(Level level, BlockPos pos) {
        return level != null && pos != null && isKnownRoadSurface(level.getBlockState(pos.above()));
    }

    private static boolean isKnownRoadSurface(BlockState roadState) {
        return roadState != null
                && (roadState.is(Blocks.STONE_BRICKS)
                || roadState.is(Blocks.STONE_BRICK_SLAB)
                || roadState.is(Blocks.STONE_BRICK_STAIRS)
                || roadState.is(Blocks.SMOOTH_SANDSTONE)
                || roadState.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || roadState.is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                || roadState.is(Blocks.MUD_BRICKS)
                || roadState.is(Blocks.MUD_BRICK_SLAB)
                || roadState.is(Blocks.MUD_BRICK_STAIRS)
                || roadState.is(Blocks.SPRUCE_PLANKS)
                || roadState.is(Blocks.SPRUCE_SLAB)
                || roadState.is(Blocks.SPRUCE_STAIRS));
    }

    private static int terrainPenalty(Level level, BlockPos pos, RoadPlanningPassContext context) {
        int penalty = isSoftGround(level, pos) ? 2 : 0;
        int maxNeighborDiff = 0;
        for (BlockPos nearby : List.of(pos.north(), pos.south(), pos.east(), pos.west())) {
            BlockPos neighborSurface = findSurface(level, nearby.getX(), nearby.getZ(), context);
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
