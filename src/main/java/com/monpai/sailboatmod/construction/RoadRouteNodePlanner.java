package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.Function;

public final class RoadRouteNodePlanner {
    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}
    };
    private static final int MAX_VISITED_NODES = 32_000;
    private static final int MAX_STEP_HEIGHT = 5;
    private static final int MAX_CONTIGUOUS_BRIDGE_COLUMNS = 5;
    private static final int MAX_TOTAL_BRIDGE_COLUMNS = 14;
    private static final double MAX_BRIDGE_SHARE = 0.20D;
    private static final double HEIGHT_PENALTY = 2.5D;
    private static final double BRIDGE_PENALTY = 7.5D;
    private static final double WATER_PENALTY = 2.25D;
    private static final double TERRAIN_PENALTY = 1.4D;
    private static final double TURN_PENALTY = 0.6D;
    private static final double PREFERRED_BONUS = 0.85D;

    private RoadRouteNodePlanner() {
    }

    public static RoutePlan plan(RouteMap map) {
        Objects.requireNonNull(map, "map");
        RoutePlan landOnly = search(map, false);
        return landOnly.path().isEmpty() ? search(map, true) : landOnly;
    }

    private static RoutePlan search(RouteMap map, boolean allowBridgeColumns) {
        RouteColumn start = map.columnAt(map.start().getX(), map.start().getZ());
        RouteColumn end = map.columnAt(map.end().getX(), map.end().getZ());
        if (!isTraversable(start, allowBridgeColumns) || !isTraversable(end, allowBridgeColumns)) {
            return RoutePlan.empty();
        }

        RoadBridgeBudgetState startBudget = RoadBridgeBudgetState.empty().advance(
                start.requiresBridge(),
                MAX_CONTIGUOUS_BRIDGE_COLUMNS,
                MAX_TOTAL_BRIDGE_COLUMNS
        );
        if (!startBudget.accepted()) {
            return RoutePlan.empty();
        }

        PriorityQueue<PathNode> open = new PriorityQueue<>(Comparator.comparingDouble(PathNode::fCost));
        Map<PathStateKey, PathNode> seen = new HashMap<>();
        PathNode startNode = new PathNode(
                start,
                null,
                0.0D,
                heuristic(start.surfacePos(), end.surfacePos()),
                startBudget,
                startBudget.contiguousBridgeColumns(),
                1
        );
        open.add(startNode);
        seen.put(new PathStateKey(columnKey(start.surfacePos()), startBudget.contiguousBridgeColumns(), startBudget.totalBridgeColumns(), 1), startNode);

        int visited = 0;
        while (!open.isEmpty() && visited++ < MAX_VISITED_NODES) {
            PathNode current = open.poll();
            if (current.closed()) {
                continue;
            }
            current.close();

            if (sameColumn(current.column().surfacePos(), end.surfacePos())) {
                List<BlockPos> candidatePath = reconstructPath(current);
                if (bridgeShareWithinLimit(candidatePath.size(), current.bridgeBudget().totalBridgeColumns())) {
                    return new RoutePlan(
                            candidatePath,
                            current.bridgeBudget().totalBridgeColumns() > 0,
                            current.bridgeBudget().totalBridgeColumns(),
                            current.longestBridgeRun()
                    );
                }
                continue;
            }

            for (int[] direction : DIRECTIONS) {
                int nextX = current.column().surfacePos().getX() + direction[0];
                int nextZ = current.column().surfacePos().getZ() + direction[1];
                if (!map.contains(nextX, nextZ)) {
                    continue;
                }

                RouteColumn next = map.columnAt(nextX, nextZ);
                if (!isTraversable(next, allowBridgeColumns)
                        || Math.abs(next.surfacePos().getY() - current.column().surfacePos().getY()) > MAX_STEP_HEIGHT) {
                    continue;
                }
                if (containsColumn(current, next.surfacePos())) {
                    continue;
                }
                if (direction[0] != 0 && direction[1] != 0 && cutsCorner(map, current.column().surfacePos(), direction, allowBridgeColumns)) {
                    continue;
                }

                RoadBridgeBudgetState nextBudget = current.bridgeBudget().advance(
                        next.requiresBridge(),
                        MAX_CONTIGUOUS_BRIDGE_COLUMNS,
                        MAX_TOTAL_BRIDGE_COLUMNS
                );
                if (!nextBudget.accepted()) {
                    continue;
                }

                double newG = current.gCost() + stepCost(current, next);
                int longestBridgeRun = Math.max(current.longestBridgeRun(), nextBudget.contiguousBridgeColumns());
                int nextStepCount = current.stepCount() + 1;
                PathStateKey key = new PathStateKey(
                        columnKey(next.surfacePos()),
                        nextBudget.contiguousBridgeColumns(),
                        nextBudget.totalBridgeColumns(),
                        nextStepCount
                );
                PathNode existing = seen.get(key);
                if (existing == null) {
                    PathNode created = new PathNode(
                            next,
                            current,
                            newG,
                            heuristic(next.surfacePos(), end.surfacePos()),
                            nextBudget,
                            longestBridgeRun,
                            nextStepCount
                    );
                    seen.put(key, created);
                    open.add(created);
                } else if (!existing.closed() && newG < existing.gCost()) {
                    existing.reopen(current, newG, heuristic(next.surfacePos(), end.surfacePos()), nextBudget, longestBridgeRun, nextStepCount);
                    open.add(existing);
                }
            }
        }

        return RoutePlan.empty();
    }

    private static boolean cutsCorner(RouteMap map, BlockPos currentPos, int[] direction, boolean allowBridgeColumns) {
        RouteColumn horizontal = map.columnAt(currentPos.getX() + direction[0], currentPos.getZ());
        RouteColumn vertical = map.columnAt(currentPos.getX(), currentPos.getZ() + direction[1]);
        return !isTraversable(horizontal, allowBridgeColumns) || !isTraversable(vertical, allowBridgeColumns);
    }

    private static boolean isTraversable(RouteColumn column, boolean allowBridgeColumns) {
        return column != null
                && column.surfacePos() != null
                && !column.blocked()
                && (allowBridgeColumns || !column.requiresBridge());
    }

    private static double stepCost(PathNode current, RouteColumn next) {
        BlockPos currentPos = current.column().surfacePos();
        BlockPos nextPos = next.surfacePos();
        boolean diagonal = currentPos.getX() != nextPos.getX() && currentPos.getZ() != nextPos.getZ();
        int heightDiff = Math.abs(nextPos.getY() - currentPos.getY());

        double cost = diagonal ? 1.45D : 1.0D;
        cost += heightDiff * heightDiff * HEIGHT_PENALTY;
        cost += next.adjacentWaterColumns() * WATER_PENALTY;
        cost += next.terrainPenalty() * TERRAIN_PENALTY;
        if (next.requiresBridge()) {
            cost += BRIDGE_PENALTY;
        }
        if (next.preferred()) {
            cost = Math.max(0.35D, cost - PREFERRED_BONUS);
        }
        if (current.parent() != null && directionChanged(current.parent().column().surfacePos(), currentPos, nextPos)) {
            cost += TURN_PENALTY;
        }
        return cost;
    }

    private static boolean directionChanged(BlockPos previous, BlockPos current, BlockPos next) {
        int firstDx = Integer.compare(current.getX(), previous.getX());
        int firstDz = Integer.compare(current.getZ(), previous.getZ());
        int secondDx = Integer.compare(next.getX(), current.getX());
        int secondDz = Integer.compare(next.getZ(), current.getZ());
        return firstDx != secondDx || firstDz != secondDz;
    }

    private static double heuristic(BlockPos from, BlockPos to) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        int dy = Math.abs(to.getY() - from.getY());
        return Math.sqrt(dx * (double) dx + dz * (double) dz) + dy * 0.35D;
    }

    private static List<BlockPos> reconstructPath(PathNode node) {
        List<BlockPos> path = new ArrayList<>();
        PathNode current = node;
        while (current != null) {
            path.add(current.column().surfacePos());
            current = current.parent();
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    private static boolean sameColumn(BlockPos left, BlockPos right) {
        return left.getX() == right.getX() && left.getZ() == right.getZ();
    }

    private static boolean containsColumn(PathNode node, BlockPos candidate) {
        PathNode current = node;
        while (current != null) {
            if (sameColumn(current.column().surfacePos(), candidate)) {
                return true;
            }
            current = current.parent();
        }
        return false;
    }

    private static boolean bridgeShareWithinLimit(int pathLength, int totalBridgeColumns) {
        return totalBridgeColumns == 0
                || (pathLength > 0 && (totalBridgeColumns / (double) pathLength) <= MAX_BRIDGE_SHARE);
    }

    private static long columnKey(BlockPos pos) {
        return columnKey(pos.getX(), pos.getZ());
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    public record RouteColumn(BlockPos surfacePos,
                              boolean blocked,
                              boolean requiresBridge,
                              int adjacentWaterColumns,
                              int terrainPenalty,
                              boolean preferred) {
        public RouteColumn {
            if (adjacentWaterColumns < 0) {
                throw new IllegalArgumentException("adjacentWaterColumns must be >= 0");
            }
            if (terrainPenalty < 0) {
                throw new IllegalArgumentException("terrainPenalty must be >= 0");
            }
        }
    }

    public record RoutePlan(List<BlockPos> path,
                            boolean usedBridge,
                            int totalBridgeColumns,
                            int longestBridgeRun) {
        public RoutePlan {
            path = path == null ? List.of() : List.copyOf(path);
        }

        public static RoutePlan empty() {
            return new RoutePlan(List.of(), false, 0, 0);
        }
    }

    public static final class RouteMap {
        private final BlockPos start;
        private final BlockPos end;
        private final SearchBounds bounds;
        private final Function<BlockPos, RouteColumn> sampler;
        private final Map<Long, RouteColumn> cache = new HashMap<>();

        private RouteMap(BlockPos start, BlockPos end, SearchBounds bounds, Function<BlockPos, RouteColumn> sampler) {
            this.start = Objects.requireNonNull(start, "start").immutable();
            this.end = Objects.requireNonNull(end, "end").immutable();
            this.bounds = Objects.requireNonNull(bounds, "bounds");
            this.sampler = Objects.requireNonNull(sampler, "sampler");
        }

        public static RouteMap of(BlockPos start, BlockPos end, Function<BlockPos, RouteColumn> sampler) {
            return new RouteMap(start, end, SearchBounds.around(start, end), sampler);
        }

        BlockPos start() {
            return start;
        }

        BlockPos end() {
            return end;
        }

        boolean contains(int x, int z) {
            return bounds.contains(x, z);
        }

        RouteColumn columnAt(int x, int z) {
            if (!contains(x, z)) {
                return new RouteColumn(null, true, false, 0, 0, false);
            }
            return cache.computeIfAbsent(columnKey(x, z), key -> {
                RouteColumn sampled = sampler.apply(new BlockPos(x, 0, z));
                return sampled == null ? new RouteColumn(null, true, false, 0, 0, false) : sampled;
            });
        }
    }

    private record SearchBounds(int minX, int maxX, int minZ, int maxZ) {
        private static SearchBounds around(BlockPos start, BlockPos end) {
            int dx = Math.abs(end.getX() - start.getX());
            int dz = Math.abs(end.getZ() - start.getZ());
            int dominantSpan = Math.max(dx, dz);
            int longitudinalMargin = Math.max(8, (dominantSpan / 3) + 4);
            int lateralMargin = Math.max(18, (dominantSpan / 2) + 4);
            int xMargin = dx > dz ? longitudinalMargin : lateralMargin;
            int zMargin = dz > dx ? longitudinalMargin : lateralMargin;
            return new SearchBounds(
                    Math.min(start.getX(), end.getX()) - xMargin,
                    Math.max(start.getX(), end.getX()) + xMargin,
                    Math.min(start.getZ(), end.getZ()) - zMargin,
                    Math.max(start.getZ(), end.getZ()) + zMargin
            );
        }

        private boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private record PathStateKey(long columnKey, int contiguousBridgeColumns, int totalBridgeColumns, int stepCount) {
    }

    private static final class PathNode {
        private final RouteColumn column;
        private PathNode parent;
        private double gCost;
        private double fCost;
        private RoadBridgeBudgetState bridgeBudget;
        private int longestBridgeRun;
        private int stepCount;
        private boolean closed;

        private PathNode(RouteColumn column,
                         PathNode parent,
                         double gCost,
                         double hCost,
                         RoadBridgeBudgetState bridgeBudget,
                         int longestBridgeRun,
                         int stepCount) {
            this.column = column;
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
            this.bridgeBudget = bridgeBudget;
            this.longestBridgeRun = longestBridgeRun;
            this.stepCount = stepCount;
        }

        private RouteColumn column() {
            return column;
        }

        private PathNode parent() {
            return parent;
        }

        private double gCost() {
            return gCost;
        }

        private double fCost() {
            return fCost;
        }

        private RoadBridgeBudgetState bridgeBudget() {
            return bridgeBudget;
        }

        private int longestBridgeRun() {
            return longestBridgeRun;
        }

        private int stepCount() {
            return stepCount;
        }

        private boolean closed() {
            return closed;
        }

        private void close() {
            this.closed = true;
        }

        private void reopen(PathNode parent,
                            double gCost,
                            double hCost,
                            RoadBridgeBudgetState bridgeBudget,
                            int longestBridgeRun,
                            int stepCount) {
            this.parent = parent;
            this.gCost = gCost;
            this.fCost = gCost + hCost;
            this.bridgeBudget = bridgeBudget;
            this.longestBridgeRun = longestBridgeRun;
            this.stepCount = stepCount;
            this.closed = false;
        }
    }
}
