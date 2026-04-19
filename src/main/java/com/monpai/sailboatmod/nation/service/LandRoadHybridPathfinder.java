package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

public final class LandRoadHybridPathfinder {
    private static final int MAX_STEP_UP = 3;
    private static final int MAX_STEP_DOWN = 3;

    private LandRoadHybridPathfinder() {
    }

    public static RoadPathfinder.PlannedPathResult find(Level level,
                                                        BlockPos from,
                                                        BlockPos to,
                                                        Set<Long> blockedColumns,
                                                        Set<Long> excludedColumns,
                                                        RoadPlanningPassContext context) {
        if (level == null || from == null || to == null) {
            return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
        }

        LandTerrainSamplingCache cache = new LandTerrainSamplingCache(level, context);
        BlockPos start = cache.surface(from.getX(), from.getZ());
        BlockPos goal = cache.surface(to.getX(), to.getZ());
        if (start == null || goal == null) {
            return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }
        Set<Long> blockedForTraversal = mergeBlockedColumns(blockedColumns, excludedColumns);
        Set<Long> unblockedEndpointColumns = unblockEndpoints(start, goal, blockedForTraversal);
        if (!isTraversable(level, start, unblockedEndpointColumns, context)
                || !isTraversable(level, goal, unblockedEndpointColumns, context)) {
            return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE);
        }

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        Map<Long, Node> best = new HashMap<>();
        Node startNode = new Node(start, null, 0.0D, heuristic(start, goal));
        open.add(startNode);
        best.put(start.asLong(), startNode);

        while (!open.isEmpty()) {
            RoadPlanningTaskService.throwIfCancelled();
            Node current = open.poll();
            if (current == null) {
                continue;
            }
            if (canFinishOnFoot(level, current.pos(), goal, blockedForTraversal, context)) {
                return new RoadPathfinder.PlannedPathResult(rebuild(current, goal), RoadPlanningFailureReason.NONE);
            }
            for (BlockPos next : neighbors(level, current.pos(), cache, blockedForTraversal, context)) {
                int elevationDelta = next == null ? Integer.MAX_VALUE : Math.abs(next.getY() - current.pos().getY());
                if (next == null || elevationDelta > (next.getY() >= current.pos().getY() ? MAX_STEP_UP : MAX_STEP_DOWN)) {
                    continue;
                }
                double gScore = current.gScore() + LandPathCostModel.moveCost(
                        orthogonalOrDiagonalCost(current.pos(), next),
                        elevationDelta,
                        cache.stability(next),
                        cache.nearWater(next),
                        deviationCost(from, to, next)
                );
                Node known = best.get(next.asLong());
                if (known != null && known.gScore() <= gScore) {
                    continue;
                }
                Node candidate = new Node(next, current, gScore, gScore + heuristic(next, goal));
                best.put(next.asLong(), candidate);
                open.add(candidate);
            }
        }
        return new RoadPathfinder.PlannedPathResult(List.of(), RoadPlanningFailureReason.SEARCH_EXHAUSTED);
    }

    private static boolean canFinishOnFoot(Level level,
                                           BlockPos current,
                                           BlockPos goal,
                                           Set<Long> blockedColumns,
                                           RoadPlanningPassContext context) {
        if (level == null || current == null || goal == null) {
            return false;
        }
        int dx = Math.abs(goal.getX() - current.getX());
        int dz = Math.abs(goal.getZ() - current.getZ());
        if (Math.max(dx, dz) > 1) {
            return false;
        }
        int elevationDelta = Math.abs(goal.getY() - current.getY());
        if (elevationDelta > (goal.getY() >= current.getY() ? MAX_STEP_UP : MAX_STEP_DOWN)) {
            return false;
        }
        Set<Long> unblockedGoalColumns = unblockEndpoints(current, goal, blockedColumns);
        RoadPathfinder.ColumnDiagnostics goalDiagnostics = RoadPathfinder.describeColumnForGroundPath(
                level,
                goal,
                unblockedGoalColumns,
                context
        );
        return goalDiagnostics.surface() != null && !goalDiagnostics.blocked();
    }

    private static Set<Long> mergeBlockedColumns(Set<Long> blockedColumns, Set<Long> excludedColumns) {
        if ((blockedColumns == null || blockedColumns.isEmpty()) && (excludedColumns == null || excludedColumns.isEmpty())) {
            return Set.of();
        }
        HashSet<Long> merged = new HashSet<>();
        if (blockedColumns != null) {
            merged.addAll(blockedColumns);
        }
        if (excludedColumns != null) {
            merged.addAll(excludedColumns);
        }
        return merged.isEmpty() ? Set.of() : Set.copyOf(merged);
    }

    private static Set<Long> unblockEndpoints(BlockPos current, BlockPos goal, Set<Long> blockedColumns) {
        if (blockedColumns == null || blockedColumns.isEmpty()) {
            return Set.of();
        }
        HashSet<Long> merged = new HashSet<>(blockedColumns);
        if (current != null) {
            merged.remove(BlockPos.asLong(current.getX(), 0, current.getZ()));
        }
        if (goal != null) {
            merged.remove(BlockPos.asLong(goal.getX(), 0, goal.getZ()));
        }
        return merged.isEmpty() ? Set.of() : Set.copyOf(merged);
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        return from.distManhattan(to);
    }

    private static int orthogonalOrDiagonalCost(BlockPos from, BlockPos to) {
        return (from.getX() != to.getX() && from.getZ() != to.getZ()) ? 14 : 10;
    }

    private static double deviationCost(BlockPos start, BlockPos end, BlockPos current) {
        double dx = end.getX() - start.getX();
        double dz = end.getZ() - start.getZ();
        double length = Math.max(1.0D, Math.hypot(dx, dz));
        double cross = Math.abs(((current.getX() - start.getX()) * dz) - ((current.getZ() - start.getZ()) * dx));
        return cross / length;
    }

    private static List<BlockPos> rebuild(Node node, BlockPos target) {
        ArrayList<BlockPos> path = new ArrayList<>();
        Node cursor = node;
        while (cursor != null) {
            path.add(0, cursor.pos().immutable());
            cursor = cursor.parent();
        }
        if (path.isEmpty() || !path.get(path.size() - 1).equals(target)) {
            path.add(target.immutable());
        }
        return List.copyOf(path);
    }

    private static List<BlockPos> neighbors(Level level,
                                            BlockPos pos,
                                            LandTerrainSamplingCache cache,
                                            Set<Long> blockedColumns,
                                            RoadPlanningPassContext context) {
        ArrayList<BlockPos> out = new ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int x = pos.getX() + dx;
                int z = pos.getZ() + dz;
                BlockPos surface = cache.surface(x, z);
                if (surface != null && isTraversable(level, surface, blockedColumns, context)) {
                    out.add(surface);
                }
            }
        }
        return out;
    }

    private static boolean isTraversable(Level level,
                                         BlockPos candidate,
                                         Set<Long> blockedColumns,
                                         RoadPlanningPassContext context) {
        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForGroundPath(
                level,
                candidate,
                blockedColumns == null ? Set.of() : blockedColumns,
                context
        );
        return diagnostics.surface() != null && !diagnostics.blocked();
    }

    private record Node(BlockPos pos, Node parent, double gScore, double score) {
    }
}
