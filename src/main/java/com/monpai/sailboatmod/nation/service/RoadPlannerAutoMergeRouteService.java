package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class RoadPlannerAutoMergeRouteService {
    public static final int DEFAULT_ENTRY_RADIUS = 64;
    public static final int DEFAULT_DESTINATION_RADIUS = 96;
    private static final double DIRECTION_WEIGHT = 100_000.0D;

    public enum Status {
        FOUND,
        NOT_FOUND,
        INVALID_REQUEST,
        SCOPE_BLOCKED
    }

    public record PreferredEntry(String roadId, int pathIndex) {
        public PreferredEntry {
            roadId = roadId == null ? "" : roadId.trim().toLowerCase(Locale.ROOT);
            pathIndex = Math.max(-1, pathIndex);
        }

        public static PreferredEntry none() {
            return new PreferredEntry("", -1);
        }

        public boolean present() {
            return !roadId.isBlank();
        }
    }

    public record Query(RoadGraphRepository repository,
                        String dimensionId,
                        List<BlockPos> routeNodes,
                        List<RoadPlannerSegmentType> routeSegments,
                        BlockPos destinationPos,
                        RoadPlannerMergeScope scope,
                        PreferredEntry preferredEntry,
                        int entryRadius,
                        int destinationRadius) {
        public Query {
            dimensionId = normalizeDimension(dimensionId);
            routeNodes = copyPositions(routeNodes);
            routeSegments = routeSegments == null ? List.of() : List.copyOf(routeSegments);
            destinationPos = destinationPos == null ? null : destinationPos.immutable();
            scope = scope == null ? RoadPlannerMergeScope.DISABLED : scope;
            preferredEntry = preferredEntry == null ? PreferredEntry.none() : preferredEntry;
            entryRadius = Math.max(1, entryRadius);
            destinationRadius = Math.max(1, destinationRadius);
        }
    }

    public record Result(Status status,
                         RoadPlannerMergeSelection mergeSelection,
                         List<BlockPos> displayPath,
                         List<RoadPlannerSharedRoadSpan> sharedSpans,
                         List<String> roadIds,
                         String message) {
        public Result {
            status = status == null ? Status.NOT_FOUND : status;
            mergeSelection = mergeSelection == null ? RoadPlannerMergeSelection.none() : mergeSelection;
            displayPath = copyPositions(displayPath);
            sharedSpans = sharedSpans == null ? List.of() : sharedSpans.stream()
                    .filter(Objects::nonNull)
                    .filter(RoadPlannerSharedRoadSpan::present)
                    .toList();
            roadIds = roadIds == null ? List.of() : roadIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(id -> id.trim().toLowerCase(Locale.ROOT))
                    .distinct()
                    .toList();
            message = message == null ? "" : message.trim();
        }

        public static Result notFound(String message) {
            return new Result(Status.NOT_FOUND, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(), message);
        }
    }

    public static Result resolve(Query query) {
        if (query == null || query.repository() == null || query.routeNodes().isEmpty()
                || query.destinationPos() == null || query.dimensionId().isBlank()) {
            return new Result(Status.INVALID_REQUEST, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(),
                    "Invalid auto merge request");
        }
        if (!query.scope().enabled()) {
            return new Result(Status.SCOPE_BLOCKED, RoadPlannerMergeSelection.none(), List.of(), List.of(), List.of(),
                    "Merge scope is disabled");
        }
        if (lastSegmentBridgeLike(query.routeSegments())) {
            return Result.notFound("Bridge segments cannot auto merge into existing roads");
        }

        Graph graph = buildGraph(query.repository(), query.dimensionId());
        if (graph.nodes().isEmpty() || graph.edges().isEmpty()) {
            return Result.notFound("No built road graph is available");
        }

        BlockPos currentEndpoint = query.routeNodes().get(query.routeNodes().size() - 1);
        Direction routeDirection = routeDirection(query.routeNodes());
        List<EntryCandidate> entries = entryCandidates(query, graph, currentEndpoint, routeDirection);
        List<UUID> destinations = destinationNodes(query.destinationPos(), query.destinationRadius(), graph);
        if (entries.isEmpty() || destinations.isEmpty()) {
            return Result.notFound("No connected existing road reaches the destination");
        }

        List<RouteCandidate> routes = new ArrayList<>();
        for (EntryCandidate entry : entries) {
            for (UUID destinationNode : destinations) {
                Path path = shortestPath(entry.nodeId(), destinationNode, graph);
                if (path.nodeIds().size() >= 2) {
                    RouteCandidate candidate = toRouteCandidate(query, graph, entry, path);
                    if (candidate != null) {
                        routes.add(candidate);
                    }
                }
            }
        }
        return routes.stream()
                .min(RouteCandidate.ORDER)
                .map(RouteCandidate::result)
                .orElseGet(() -> Result.notFound("No connected existing road reaches the destination"));
    }

    private static Graph buildGraph(RoadGraphRepository repository, String dimensionId) {
        Map<UUID, RoadGraphNodeRecord> nodes = new LinkedHashMap<>();
        for (RoadGraphNodeRecord node : repository.nodes()) {
            if (node != null && normalizeDimension(node.dimensionId()).equals(dimensionId)) {
                nodes.put(node.nodeId(), node);
            }
        }
        Map<UUID, RoadGraphEdgeRecord> edges = new LinkedHashMap<>();
        Map<UUID, List<EdgeStep>> adjacency = new HashMap<>();
        Map<String, EdgeStep> stepByDirection = new HashMap<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (edge == null || !edge.built() || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
                continue;
            }
            edges.put(edge.edgeId(), edge);
            EdgeStep forward = new EdgeStep(edge, edge.fromNodeId(), edge.toNodeId(), true, length(edge.centerline()));
            EdgeStep backward = new EdgeStep(edge, edge.toNodeId(), edge.fromNodeId(), false, length(edge.centerline()));
            adjacency.computeIfAbsent(forward.fromNodeId(), ignored -> new ArrayList<>()).add(forward);
            adjacency.computeIfAbsent(backward.fromNodeId(), ignored -> new ArrayList<>()).add(backward);
            stepByDirection.put(edgeKey(forward.fromNodeId(), forward.toNodeId()), forward);
            stepByDirection.put(edgeKey(backward.fromNodeId(), backward.toNodeId()), backward);
        }
        return new Graph(nodes, edges, adjacency, stepByDirection);
    }

    private static List<EntryCandidate> entryCandidates(Query query, Graph graph, BlockPos currentEndpoint, Direction routeDirection) {
        long radiusSqr = (long) query.entryRadius() * (long) query.entryRadius();
        ArrayList<EntryCandidate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (RoadGraphEdgeRecord edge : graph.edges().values()) {
            addEntryCandidate(candidates, seen, query, graph, edge, edge.fromNodeId(), 0, currentEndpoint, routeDirection, radiusSqr);
            addEntryCandidate(candidates, seen, query, graph, edge, edge.toNodeId(), lastPathIndex(edge), currentEndpoint, routeDirection, radiusSqr);
        }
        candidates.sort(EntryCandidate.ORDER);
        return List.copyOf(candidates);
    }

    private static void addEntryCandidate(List<EntryCandidate> candidates,
                                          Set<String> seen,
                                          Query query,
                                          Graph graph,
                                          RoadGraphEdgeRecord edge,
                                          UUID nodeId,
                                          int pathIndex,
                                          BlockPos currentEndpoint,
                                          Direction routeDirection,
                                          long radiusSqr) {
        RoadGraphNodeRecord node = graph.nodes().get(nodeId);
        if (node == null) {
            return;
        }
        boolean preferred = preferredMatches(query.preferredEntry(), edge, pathIndex);
        long distanceSqr = horizontalDistanceSqr(currentEndpoint, node.pos());
        if (!preferred && distanceSqr > radiusSqr) {
            return;
        }
        String key = nodeId + ":" + edge.edgeId();
        if (!seen.add(key)) {
            return;
        }
        Direction connector = Direction.between(currentEndpoint, node.pos());
        double directionPenalty = directionPenalty(routeDirection, connector);
        candidates.add(new EntryCandidate(
                nodeId,
                edge.edgeId(),
                pathIndex,
                node.pos(),
                preferred ? 0 : 1,
                directionPenalty,
                Math.sqrt(distanceSqr)));
    }

    private static boolean preferredMatches(PreferredEntry preferred, RoadGraphEdgeRecord edge, int pathIndex) {
        if (preferred == null || !preferred.present() || edge == null) {
            return false;
        }
        if (!preferred.roadId().equals(edge.edgeId().toString().toLowerCase(Locale.ROOT))) {
            return false;
        }
        return preferred.pathIndex() < 0 || preferred.pathIndex() == pathIndex;
    }

    private static List<UUID> destinationNodes(BlockPos destinationPos, int destinationRadius, Graph graph) {
        long radiusSqr = (long) destinationRadius * (long) destinationRadius;
        return graph.nodes().values().stream()
                .filter(node -> horizontalDistanceSqr(destinationPos, node.pos()) <= radiusSqr)
                .sorted(Comparator.comparingLong(node -> horizontalDistanceSqr(destinationPos, node.pos())))
                .map(RoadGraphNodeRecord::nodeId)
                .toList();
    }

    private static Path shortestPath(UUID startNode, UUID destinationNode, Graph graph) {
        if (startNode == null || destinationNode == null || startNode.equals(destinationNode)) {
            return Path.empty();
        }
        PriorityQueue<QueueState> queue = new PriorityQueue<>(Comparator.comparingDouble(QueueState::cost));
        Map<UUID, Double> distances = new HashMap<>();
        Map<UUID, UUID> previous = new HashMap<>();
        distances.put(startNode, 0.0D);
        queue.add(new QueueState(startNode, 0.0D));
        while (!queue.isEmpty()) {
            QueueState state = queue.poll();
            if (state.cost() > distances.getOrDefault(state.nodeId(), Double.MAX_VALUE)) {
                continue;
            }
            if (state.nodeId().equals(destinationNode)) {
                break;
            }
            for (EdgeStep step : graph.adjacency().getOrDefault(state.nodeId(), List.of())) {
                double nextCost = state.cost() + Math.max(1.0D, step.length());
                if (nextCost >= distances.getOrDefault(step.toNodeId(), Double.MAX_VALUE)) {
                    continue;
                }
                distances.put(step.toNodeId(), nextCost);
                previous.put(step.toNodeId(), state.nodeId());
                queue.add(new QueueState(step.toNodeId(), nextCost));
            }
        }
        if (!distances.containsKey(destinationNode)) {
            return Path.empty();
        }
        ArrayList<UUID> reversed = new ArrayList<>();
        UUID cursor = destinationNode;
        while (cursor != null) {
            reversed.add(cursor);
            if (cursor.equals(startNode)) {
                break;
            }
            cursor = previous.get(cursor);
        }
        if (reversed.isEmpty() || !reversed.get(reversed.size() - 1).equals(startNode)) {
            return Path.empty();
        }
        ArrayList<UUID> ordered = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            ordered.add(reversed.get(index));
        }
        return new Path(List.copyOf(ordered), distances.get(destinationNode));
    }

    private static RouteCandidate toRouteCandidate(Query query, Graph graph, EntryCandidate entry, Path path) {
        ArrayList<BlockPos> displayPath = new ArrayList<>();
        ArrayList<RoadPlannerSharedRoadSpan> sharedSpans = new ArrayList<>();
        ArrayList<String> roadIds = new ArrayList<>();
        RoadPlannerMergeSelection selection = RoadPlannerMergeSelection.none();
        for (int index = 1; index < path.nodeIds().size(); index++) {
            UUID from = path.nodeIds().get(index - 1);
            UUID to = path.nodeIds().get(index);
            EdgeStep step = graph.stepByDirection().get(edgeKey(from, to));
            if (step == null) {
                return null;
            }
            List<BlockPos> oriented = step.orientedDisplayPath();
            if (oriented.size() < 2) {
                return null;
            }
            if (displayPath.isEmpty()) {
                displayPath.addAll(oriented);
            } else {
                for (int point = 1; point < oriented.size(); point++) {
                    displayPath.add(oriented.get(point));
                }
            }
            if (!roadIds.contains(step.edge().edgeId().toString())) {
                roadIds.add(step.edge().edgeId().toString());
            }
            sharedSpans.add(new RoadPlannerSharedRoadSpan(
                    step.edge().edgeId().toString(),
                    step.fromPathIndex(),
                    step.toPathIndex(),
                    oriented.get(0),
                    oriented.get(oriented.size() - 1),
                    query.scope(),
                    RoadPlannerSharedRoadSpan.Role.END_MERGE));
            if (!selection.present()) {
                selection = new RoadPlannerMergeSelection(
                        step.edge().edgeId().toString(),
                        step.fromPathIndex(),
                        oriented.get(0),
                        query.scope());
            }
        }
        if (!selection.present() || displayPath.size() < 2) {
            return null;
        }
        Result result = new Result(Status.FOUND, selection, displayPath, sharedSpans, roadIds, "Auto merge route found");
        return new RouteCandidate(result, entry.preferredRank(), entry.directionPenalty(), path.cost(), entry.entryDistance());
    }

    private static Direction routeDirection(List<BlockPos> routeNodes) {
        if (routeNodes == null || routeNodes.size() < 2) {
            return Direction.zero();
        }
        BlockPos tail = routeNodes.get(routeNodes.size() - 1);
        for (int index = routeNodes.size() - 2; index >= 0; index--) {
            Direction direction = Direction.between(routeNodes.get(index), tail);
            if (!direction.isZero()) {
                return direction;
            }
        }
        return Direction.zero();
    }

    private static double directionPenalty(Direction routeDirection, Direction connectorDirection) {
        if (routeDirection == null || connectorDirection == null || routeDirection.isZero() || connectorDirection.isZero()) {
            return 0.0D;
        }
        double dot = routeDirection.dot(connectorDirection);
        return (1.0D - dot) * DIRECTION_WEIGHT;
    }

    private static boolean lastSegmentBridgeLike(List<RoadPlannerSegmentType> routeSegments) {
        if (routeSegments == null || routeSegments.isEmpty()) {
            return false;
        }
        RoadPlannerSegmentType segment = routeSegments.get(routeSegments.size() - 1);
        return segment == RoadPlannerSegmentType.BRIDGE_SMALL
                || segment == RoadPlannerSegmentType.BRIDGE_MAJOR
                || segment == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    private static int lastPathIndex(RoadGraphEdgeRecord edge) {
        int size = edge == null || edge.centerline().isEmpty() ? 0 : edge.centerline().size();
        return Math.max(0, size - 1);
    }

    private static double length(List<BlockPos> path) {
        if (path == null || path.size() < 2) {
            return 1.0D;
        }
        double total = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            total += Math.sqrt(path.get(index - 1).distSqr(path.get(index)));
        }
        return Math.max(1.0D, total);
    }

    private static long horizontalDistanceSqr(BlockPos left, BlockPos right) {
        if (left == null || right == null) {
            return Long.MAX_VALUE;
        }
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static String edgeKey(UUID from, UUID to) {
        return from + "->" + to;
    }

    private static List<BlockPos> copyPositions(List<BlockPos> positions) {
        return positions == null ? List.of() : positions.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    private static String normalizeDimension(String dimensionId) {
        return dimensionId == null ? "" : dimensionId.trim().toLowerCase(Locale.ROOT);
    }

    private record Graph(Map<UUID, RoadGraphNodeRecord> nodes,
                         Map<UUID, RoadGraphEdgeRecord> edges,
                         Map<UUID, List<EdgeStep>> adjacency,
                         Map<String, EdgeStep> stepByDirection) {
    }

    private record EdgeStep(RoadGraphEdgeRecord edge, UUID fromNodeId, UUID toNodeId, boolean forward, double length) {
        int fromPathIndex() {
            return forward ? 0 : lastPathIndex(edge);
        }

        int toPathIndex() {
            return forward ? lastPathIndex(edge) : 0;
        }

        List<BlockPos> orientedDisplayPath() {
            List<BlockPos> source = edge.displayPath().isEmpty() ? edge.centerline() : edge.displayPath();
            if (source.size() < 2) {
                return List.of();
            }
            if (forward) {
                return source.stream().map(BlockPos::immutable).toList();
            }
            ArrayList<BlockPos> reversed = new ArrayList<>(source.size());
            for (int index = source.size() - 1; index >= 0; index--) {
                reversed.add(source.get(index).immutable());
            }
            return List.copyOf(reversed);
        }
    }

    private record EntryCandidate(UUID nodeId,
                                  UUID edgeId,
                                  int pathIndex,
                                  BlockPos anchorPos,
                                  int preferredRank,
                                  double directionPenalty,
                                  double entryDistance) {
        private static final Comparator<EntryCandidate> ORDER = Comparator
                .comparingInt(EntryCandidate::preferredRank)
                .thenComparingDouble(EntryCandidate::directionPenalty)
                .thenComparingDouble(EntryCandidate::entryDistance)
                .thenComparing(candidate -> candidate.edgeId().toString())
                .thenComparingInt(EntryCandidate::pathIndex);

        private EntryCandidate {
            anchorPos = anchorPos == null ? BlockPos.ZERO : anchorPos.immutable();
        }
    }

    private record Path(List<UUID> nodeIds, double cost) {
        static Path empty() {
            return new Path(List.of(), Double.MAX_VALUE);
        }
    }

    private record QueueState(UUID nodeId, double cost) {
    }

    private record RouteCandidate(Result result,
                                  int preferredRank,
                                  double directionPenalty,
                                  double routeCost,
                                  double entryDistance) {
        private static final Comparator<RouteCandidate> ORDER = Comparator
                .comparingInt(RouteCandidate::preferredRank)
                .thenComparingDouble(RouteCandidate::directionPenalty)
                .thenComparingDouble(RouteCandidate::routeCost)
                .thenComparingDouble(RouteCandidate::entryDistance);
    }

    private record Direction(double x, double z) {
        static Direction zero() {
            return new Direction(0.0D, 0.0D);
        }

        static Direction between(BlockPos from, BlockPos to) {
            if (from == null || to == null) {
                return zero();
            }
            double dx = to.getX() - from.getX();
            double dz = to.getZ() - from.getZ();
            double length = Math.sqrt(dx * dx + dz * dz);
            if (length <= 0.0001D) {
                return zero();
            }
            return new Direction(dx / length, dz / length);
        }

        boolean isZero() {
            return Math.abs(x) <= 0.0001D && Math.abs(z) <= 0.0001D;
        }

        double dot(Direction other) {
            return other == null ? 0.0D : x * other.x() + z * other.z();
        }
    }

    private RoadPlannerAutoMergeRouteService() {
    }
}
