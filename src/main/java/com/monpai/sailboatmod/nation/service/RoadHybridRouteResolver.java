package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;

public final class RoadHybridRouteResolver {
    private static final int MAX_ENDPOINT_NODE_CANDIDATES = 6;

    private RoadHybridRouteResolver() {
    }

    public enum ResolutionKind {
        DIRECT,
        SOURCE_CONNECTOR,
        TARGET_CONNECTOR,
        DUAL_CONNECTOR,
        NONE
    }

    public interface ConnectorPlanner {
        ConnectorResult plan(BlockPos from, BlockPos to, boolean allowWaterFallback);
    }

    public record ConnectorResult(List<BlockPos> path,
                                  int bridgeColumns,
                                  int longestBridgeRun,
                                  int adjacentWaterColumns,
                                  boolean usedWaterFallback) {
        public ConnectorResult {
            path = path == null ? List.of() : List.copyOf(path);
        }

        boolean hasPath() {
            return path.size() >= 2;
        }
    }

    public record HybridRoute(ResolutionKind kind,
                              List<BlockPos> fullPath,
                              boolean usedExistingNetwork,
                              int connectorCount,
                              int bridgeColumns,
                              int longestBridgeRun,
                              int adjacentWaterColumns,
                              double score) {
        public static HybridRoute none() {
            return new HybridRoute(
                    ResolutionKind.NONE,
                    List.of(),
                    false,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Integer.MAX_VALUE,
                    Double.MAX_VALUE
            );
        }
    }

    public static HybridRoute resolveCandidates(List<BlockPos> sourceAnchors,
                                                List<BlockPos> targetAnchors,
                                                Set<BlockPos> networkNodes,
                                                Map<BlockPos, Set<BlockPos>> adjacency,
                                                ConnectorPlanner planner) {
        Objects.requireNonNull(sourceAnchors, "sourceAnchors");
        Objects.requireNonNull(targetAnchors, "targetAnchors");
        Objects.requireNonNull(networkNodes, "networkNodes");
        Objects.requireNonNull(adjacency, "adjacency");
        Objects.requireNonNull(planner, "planner");

        HybridRoute best = HybridRoute.none();
        for (BlockPos source : sourceAnchors) {
            for (BlockPos target : targetAnchors) {
                best = chooseBetter(best, directCandidate(source, target, planner));

                List<BlockPos> sourceNodes = selectNearestNodes(source, networkNodes, MAX_ENDPOINT_NODE_CANDIDATES);
                List<BlockPos> targetNodes = selectNearestNodes(target, networkNodes, MAX_ENDPOINT_NODE_CANDIDATES);

                for (BlockPos node : sourceNodes) {
                    best = chooseBetter(best, sourceConnectorCandidate(source, target, node, adjacency, planner));
                }
                for (BlockPos node : targetNodes) {
                    best = chooseBetter(best, targetConnectorCandidate(source, target, node, adjacency, planner));
                }
                for (BlockPos leftNode : sourceNodes) {
                    for (BlockPos rightNode : targetNodes) {
                        best = chooseBetter(best, dualConnectorCandidate(source, target, leftNode, rightNode, adjacency, planner));
                    }
                }
            }
        }
        return best;
    }

    static HybridRoute resolveForTest(List<BlockPos> sourceAnchors,
                                      List<BlockPos> targetAnchors,
                                      Set<BlockPos> networkNodes,
                                      Map<BlockPos, Set<BlockPos>> adjacency,
                                      ConnectorPlanner planner) {
        return resolveCandidates(sourceAnchors, targetAnchors, networkNodes, adjacency, planner);
    }

    static List<BlockPos> selectNearestNodesForTest(BlockPos anchor, Set<BlockPos> networkNodes, int limit) {
        return selectNearestNodes(anchor, networkNodes, limit);
    }

    public static Set<BlockPos> collectNetworkNodes(List<RoadNetworkRecord> roads) {
        Set<BlockPos> nodes = new HashSet<>();
        if (roads == null) {
            return Set.of();
        }
        for (RoadNetworkRecord road : roads) {
            if (road == null || road.path() == null) {
                continue;
            }
            for (BlockPos pos : road.path()) {
                if (pos != null) {
                    nodes.add(pos.immutable());
                }
            }
        }
        return Set.copyOf(nodes);
    }

    public static Map<BlockPos, Set<BlockPos>> collectNetworkAdjacency(List<RoadNetworkRecord> roads) {
        Map<BlockPos, Set<BlockPos>> adjacency = new HashMap<>();
        if (roads == null) {
            return adjacency;
        }
        for (RoadNetworkRecord road : roads) {
            if (road == null || road.path() == null) {
                continue;
            }
            for (int i = 0; i + 1 < road.path().size(); i++) {
                BlockPos current = road.path().get(i);
                BlockPos next = road.path().get(i + 1);
                if (current == null || next == null) {
                    continue;
                }
                BlockPos currentKey = current.immutable();
                BlockPos nextKey = next.immutable();
                adjacency.computeIfAbsent(currentKey, ignored -> new HashSet<>()).add(nextKey);
                adjacency.computeIfAbsent(nextKey, ignored -> new HashSet<>()).add(currentKey);
            }
        }
        return adjacency;
    }

    private static List<BlockPos> selectNearestNodes(BlockPos anchor, Set<BlockPos> networkNodes, int limit) {
        if (anchor == null || networkNodes == null || networkNodes.isEmpty() || limit <= 0) {
            return List.of();
        }
        return networkNodes.stream()
                .filter(Objects::nonNull)
                .sorted((left, right) -> {
                    int cmp = Double.compare(anchor.distSqr(left), anchor.distSqr(right));
                    if (cmp != 0) {
                        return cmp;
                    }
                    cmp = Integer.compare(left.getX(), right.getX());
                    if (cmp != 0) {
                        return cmp;
                    }
                    return Integer.compare(left.getZ(), right.getZ());
                })
                .limit(limit)
                .map(BlockPos::immutable)
                .toList();
    }

    public static ConnectorResult summarizePath(ServerLevel level, List<BlockPos> path, boolean usedWaterFallback) {
        if (level == null || path == null || path.isEmpty()) {
            return new ConnectorResult(path, 0, 0, 0, usedWaterFallback);
        }

        int bridgeColumns = 0;
        int longestBridgeRun = 0;
        int currentBridgeRun = 0;
        int adjacentWaterColumns = 0;
        for (BlockPos pos : path) {
            RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForAnchorSelection(level, pos);
            if (diagnostics == null || diagnostics.surface() == null) {
                currentBridgeRun = 0;
                continue;
            }
            if (diagnostics.bridgeRequired()) {
                bridgeColumns++;
                currentBridgeRun++;
                longestBridgeRun = Math.max(longestBridgeRun, currentBridgeRun);
            } else {
                currentBridgeRun = 0;
            }
            adjacentWaterColumns += diagnostics.adjacentWater();
        }
        return new ConnectorResult(path, bridgeColumns, longestBridgeRun, adjacentWaterColumns, usedWaterFallback);
    }

    private static HybridRoute directCandidate(BlockPos source, BlockPos target, ConnectorPlanner planner) {
        ConnectorResult result = planner.plan(source, target, true);
        if (result == null || !result.hasPath()) {
            return HybridRoute.none();
        }
        return createRoute(
                ResolutionKind.DIRECT,
                result.path(),
                false,
                0,
                result.bridgeColumns(),
                result.longestBridgeRun(),
                result.adjacentWaterColumns()
        );
    }

    private static HybridRoute sourceConnectorCandidate(BlockPos source,
                                                        BlockPos target,
                                                        BlockPos node,
                                                        Map<BlockPos, Set<BlockPos>> adjacency,
                                                        ConnectorPlanner planner) {
        List<BlockPos> networkPath = findNetworkPath(node, target, adjacency);
        if (networkPath.isEmpty()) {
            return HybridRoute.none();
        }

        ConnectorResult connector = planner.plan(source, node, true);
        if (connector == null || !connector.hasPath()) {
            return HybridRoute.none();
        }

        return createRoute(
                ResolutionKind.SOURCE_CONNECTOR,
                stitch(connector.path(), networkPath),
                true,
                1,
                connector.bridgeColumns(),
                connector.longestBridgeRun(),
                connector.adjacentWaterColumns()
        );
    }

    private static HybridRoute targetConnectorCandidate(BlockPos source,
                                                        BlockPos target,
                                                        BlockPos node,
                                                        Map<BlockPos, Set<BlockPos>> adjacency,
                                                        ConnectorPlanner planner) {
        List<BlockPos> networkPath = findNetworkPath(source, node, adjacency);
        if (networkPath.isEmpty()) {
            return HybridRoute.none();
        }

        ConnectorResult connector = planner.plan(node, target, true);
        if (connector == null || !connector.hasPath()) {
            return HybridRoute.none();
        }

        return createRoute(
                ResolutionKind.TARGET_CONNECTOR,
                stitch(networkPath, connector.path()),
                true,
                1,
                connector.bridgeColumns(),
                connector.longestBridgeRun(),
                connector.adjacentWaterColumns()
        );
    }

    private static HybridRoute dualConnectorCandidate(BlockPos source,
                                                      BlockPos target,
                                                      BlockPos leftNode,
                                                      BlockPos rightNode,
                                                      Map<BlockPos, Set<BlockPos>> adjacency,
                                                      ConnectorPlanner planner) {
        if (leftNode == null || rightNode == null || leftNode.equals(rightNode)) {
            return HybridRoute.none();
        }
        List<BlockPos> networkPath = findNetworkPath(leftNode, rightNode, adjacency);
        if (networkPath.isEmpty()) {
            return HybridRoute.none();
        }

        ConnectorResult left = planner.plan(source, leftNode, true);
        ConnectorResult right = planner.plan(rightNode, target, true);
        if (left == null || right == null || !left.hasPath() || !right.hasPath()) {
            return HybridRoute.none();
        }

        List<BlockPos> merged = stitch(left.path(), networkPath, right.path());
        int bridgeColumns = left.bridgeColumns() + right.bridgeColumns();
        int longestBridgeRun = Math.max(left.longestBridgeRun(), right.longestBridgeRun());
        int adjacentWaterColumns = left.adjacentWaterColumns() + right.adjacentWaterColumns();
        return createRoute(
                ResolutionKind.DUAL_CONNECTOR,
                merged,
                true,
                2,
                bridgeColumns,
                longestBridgeRun,
                adjacentWaterColumns
        );
    }

    private static HybridRoute createRoute(ResolutionKind kind,
                                           List<BlockPos> path,
                                           boolean usedExistingNetwork,
                                           int connectorCount,
                                           int bridgeColumns,
                                           int longestBridgeRun,
                                           int adjacentWaterColumns) {
        if (path == null || path.size() < 2 || !isContinuousPath(path)) {
            return HybridRoute.none();
        }
        return new HybridRoute(
                kind,
                List.copyOf(path),
                usedExistingNetwork,
                connectorCount,
                bridgeColumns,
                longestBridgeRun,
                adjacentWaterColumns,
                score(path, usedExistingNetwork, connectorCount, bridgeColumns, longestBridgeRun, adjacentWaterColumns)
        );
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
            int dy = Math.abs(current.getY() - previous.getY());
            int dz = Math.abs(current.getZ() - previous.getZ());
            if (Math.max(dx, Math.max(dy, dz)) > 1) {
                return false;
            }
        }
        return true;
    }

    private static HybridRoute chooseBetter(HybridRoute current, HybridRoute candidate) {
        if (candidate == null || candidate.kind() == ResolutionKind.NONE) {
            return current;
        }
        if (current == null || current.kind() == ResolutionKind.NONE) {
            return candidate;
        }
        if (candidate.usedExistingNetwork() != current.usedExistingNetwork()) {
            return candidate.usedExistingNetwork() ? candidate : current;
        }
        if (candidate.connectorCount() != current.connectorCount()) {
            return candidate.connectorCount() < current.connectorCount() ? candidate : current;
        }
        return candidate.score() < current.score() ? candidate : current;
    }

    private static double score(List<BlockPos> path,
                                boolean usedExistingNetwork,
                                int connectorCount,
                                int bridgeColumns,
                                int longestBridgeRun,
                                int adjacentWaterColumns) {
        double score = path.size();
        score += bridgeColumns * 6.0D;
        score += longestBridgeRun * 8.0D;
        score += adjacentWaterColumns * 1.5D;
        score += connectorCount * 5.0D;
        if (!usedExistingNetwork) {
            score += 1000.0D;
        }
        return score;
    }

    private static List<BlockPos> findNetworkPath(BlockPos start, BlockPos end, Map<BlockPos, Set<BlockPos>> adjacency) {
        if (start == null || end == null) {
            return List.of();
        }
        if (start.equals(end)) {
            return List.of(start.immutable());
        }

        Queue<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        Set<BlockPos> visited = new HashSet<>();
        BlockPos startKey = start.immutable();
        BlockPos endKey = end.immutable();
        queue.add(startKey);
        visited.add(startKey);

        while (!queue.isEmpty()) {
            BlockPos current = queue.remove();
            for (BlockPos neighbor : adjacency.getOrDefault(current, Set.of())) {
                BlockPos neighborKey = neighbor.immutable();
                if (!visited.add(neighborKey)) {
                    continue;
                }
                parent.put(neighborKey, current);
                if (neighborKey.equals(endKey)) {
                    return rebuildPath(startKey, endKey, parent);
                }
                queue.add(neighborKey);
            }
        }
        return List.of();
    }

    private static List<BlockPos> rebuildPath(BlockPos start, BlockPos end, Map<BlockPos, BlockPos> parent) {
        List<BlockPos> reversed = new ArrayList<>();
        BlockPos cursor = end;
        reversed.add(end.immutable());
        while (!cursor.equals(start)) {
            cursor = parent.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            reversed.add(cursor.immutable());
        }

        List<BlockPos> ordered = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ordered.add(reversed.get(i));
        }
        return List.copyOf(ordered);
    }

    @SafeVarargs
    private static List<BlockPos> stitch(List<BlockPos>... segments) {
        LinkedHashSet<BlockPos> merged = new LinkedHashSet<>();
        for (List<BlockPos> segment : segments) {
            if (segment == null) {
                continue;
            }
            for (BlockPos pos : segment) {
                if (pos != null) {
                    merged.add(pos.immutable());
                }
            }
        }
        return List.copyOf(merged);
    }
}
