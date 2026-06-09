package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSpatialIndex;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

public final class RoadGraphRoutingService {
    private final RoadGraphRepository repository;

    public RoadGraphRoutingService(RoadGraphRepository repository) {
        this.repository = repository;
    }

    public List<BlockPos> route(String dimensionId, BlockPos start, BlockPos end, int connectorRadius) {
        if (repository == null || start == null || end == null) {
            return List.of();
        }
        Graph graph = buildGraph(dimensionId);
        Attachment startAttachment = nearestAttachment(dimensionId, start, graph, connectorRadius);
        Attachment endAttachment = nearestAttachment(dimensionId, end, graph, connectorRadius);
        if (startAttachment == null || endAttachment == null) {
            return List.of();
        }
        RouteCandidate candidate = routeBetweenAttachments(startAttachment, endAttachment, graph);
        return candidate == null || candidate.path().size() < 2 ? List.of() : candidate.path();
    }

    public boolean isRoadCorridor(String dimensionId, BlockPos pos, int radiusBlocks) {
        return repository != null && repository.spatialIndex().isRoadCorridor(dimensionId, pos, radiusBlocks);
    }

    private Graph buildGraph(String dimensionId) {
        Map<UUID, RoadGraphNodeRecord> nodes = new HashMap<>();
        for (RoadGraphNodeRecord node : repository.nodes()) {
            if (node.dimensionId().equalsIgnoreCase(dimensionId)) {
                nodes.put(node.nodeId(), node);
            }
        }
        Map<UUID, Set<RouteNeighbor>> adjacency = new HashMap<>();
        Map<String, EdgeStep> edgeBetween = new HashMap<>();
        for (RoadGraphEdgeRecord edge : repository.edgesForDimension(dimensionId)) {
            if (!edge.built() || !nodes.containsKey(edge.fromNodeId()) || !nodes.containsKey(edge.toNodeId())) {
                continue;
            }
            double length = length(edge.centerline());
            adjacency.computeIfAbsent(edge.fromNodeId(), ignored -> new HashSet<>()).add(new RouteNeighbor(edge.toNodeId(), length));
            adjacency.computeIfAbsent(edge.toNodeId(), ignored -> new HashSet<>()).add(new RouteNeighbor(edge.fromNodeId(), length));
            edgeBetween.put(edgeKey(edge.fromNodeId(), edge.toNodeId()), new EdgeStep(edge, true));
            edgeBetween.put(edgeKey(edge.toNodeId(), edge.fromNodeId()), new EdgeStep(edge, false));
        }
        return new Graph(nodes, adjacency, edgeBetween);
    }

    private Attachment nearestAttachment(String dimensionId, BlockPos origin, Graph graph, int radius) {
        NodeHit nodeHit = nearestNode(origin, graph.nodes(), radius);
        RoadGraphSpatialIndex.EdgeHit edgeHit = repository.spatialIndex().near(dimensionId, origin, Math.max(0, radius))
                .stream()
                .findFirst()
                .orElse(null);
        if (nodeHit != null && (edgeHit == null || nodeHit.distanceSqr() <= edgeHit.distanceSqr())) {
            RoadGraphNodeRecord node = graph.nodes().get(nodeHit.nodeId());
            return node == null ? null : new Attachment(origin.immutable(), node.nodeId(), null, -1, node.pos());
        }
        if (edgeHit == null || edgeHit.edge().centerline().isEmpty()) {
            return null;
        }
        int centerlineIndex = nearestCenterlineIndex(edgeHit.edge(), edgeHit.pos());
        return new Attachment(
                origin.immutable(),
                null,
                edgeHit.edge(),
                centerlineIndex,
                edgeHit.edge().centerline().get(centerlineIndex)
        );
    }

    private static RouteCandidate routeBetweenAttachments(Attachment start, Attachment end, Graph graph) {
        RouteCandidate best = null;
        if (start.edge() != null && end.edge() != null && start.edge().edgeId().equals(end.edge().edgeId())) {
            List<BlockPos> direct = new ArrayList<>();
            addDeduped(direct, start.origin());
            addAllDeduped(direct, centerlineRange(start.edge().centerline(), start.centerlineIndex(), end.centerlineIndex()));
            addDeduped(direct, end.origin());
            best = new RouteCandidate(List.copyOf(direct), pathLength(direct));
        }

        for (EndpointOption startOption : endpointOptions(start, true)) {
            for (EndpointOption endOption : endpointOptions(end, false)) {
                List<UUID> nodePath = dijkstra(startOption.nodeId(), endOption.nodeId(), graph.adjacency());
                if (nodePath.isEmpty()) {
                    continue;
                }
                List<BlockPos> path = new ArrayList<>();
                addAllDeduped(path, startOption.path());
                addGraphPath(path, nodePath, graph);
                addAllDeduped(path, endOption.path());
                RouteCandidate candidate = new RouteCandidate(List.copyOf(path), pathLength(path));
                if (best == null || candidate.length() < best.length()) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    private static List<EndpointOption> endpointOptions(Attachment attachment, boolean source) {
        if (attachment.nodeId() != null) {
            return List.of(new EndpointOption(attachment.nodeId(), List.of(attachment.origin(), attachment.roadPos())));
        }
        RoadGraphEdgeRecord edge = attachment.edge();
        if (edge == null || edge.centerline().isEmpty()) {
            return List.of();
        }
        return List.of(
                new EndpointOption(edge.fromNodeId(), edgeAttachmentPath(attachment, edge.fromNodeId(), source)),
                new EndpointOption(edge.toNodeId(), edgeAttachmentPath(attachment, edge.toNodeId(), source))
        );
    }

    private static List<BlockPos> edgeAttachmentPath(Attachment attachment, UUID endpointId, boolean source) {
        RoadGraphEdgeRecord edge = attachment.edge();
        boolean endpointIsFrom = endpointId.equals(edge.fromNodeId());
        int endpointIndex = endpointIsFrom ? 0 : edge.centerline().size() - 1;
        List<BlockPos> range = source
                ? centerlineRange(edge.centerline(), attachment.centerlineIndex(), endpointIndex)
                : centerlineRange(edge.centerline(), endpointIndex, attachment.centerlineIndex());
        List<BlockPos> out = new ArrayList<>();
        if (source) {
            addDeduped(out, attachment.origin());
            addAllDeduped(out, range);
        } else {
            addAllDeduped(out, range);
            addDeduped(out, attachment.origin());
        }
        return List.copyOf(out);
    }

    private static void addGraphPath(List<BlockPos> out, List<UUID> nodePath, Graph graph) {
        if (nodePath.size() == 1) {
            RoadGraphNodeRecord node = graph.nodes().get(nodePath.get(0));
            if (node != null) {
                addDeduped(out, node.pos());
            }
            return;
        }
        for (int index = 1; index < nodePath.size(); index++) {
            EdgeStep step = graph.edgeBetween().get(edgeKey(nodePath.get(index - 1), nodePath.get(index)));
            if (step != null) {
                addAllDeduped(out, step.forward() ? step.edge().centerline() : reversed(step.edge().centerline()));
            }
        }
    }

    private static NodeHit nearestNode(BlockPos origin, Map<UUID, RoadGraphNodeRecord> nodes, int radius) {
        UUID best = null;
        long bestDistance = (long) Math.max(0, radius) * (long) Math.max(0, radius);
        for (RoadGraphNodeRecord node : nodes.values()) {
            long distance = dist2XZ(origin, node.pos());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = node.nodeId();
            }
        }
        return best == null ? null : new NodeHit(best, bestDistance);
    }

    private static int nearestCenterlineIndex(RoadGraphEdgeRecord edge, BlockPos pos) {
        int bestIndex = 0;
        long bestDistance = Long.MAX_VALUE;
        List<BlockPos> centerline = edge.centerline();
        for (int index = 0; index < centerline.size(); index++) {
            long distance = dist2XZ(pos, centerline.get(index));
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static List<BlockPos> centerlineRange(List<BlockPos> centerline, int fromIndex, int toIndex) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        int from = Math.max(0, Math.min(centerline.size() - 1, fromIndex));
        int to = Math.max(0, Math.min(centerline.size() - 1, toIndex));
        List<BlockPos> out = new ArrayList<>();
        int step = from <= to ? 1 : -1;
        for (int index = from; ; index += step) {
            addDeduped(out, centerline.get(index));
            if (index == to) {
                break;
            }
        }
        return List.copyOf(out);
    }

    private static void addAllDeduped(List<BlockPos> out, List<BlockPos> positions) {
        for (BlockPos position : positions == null ? List.<BlockPos>of() : positions) {
            addDeduped(out, position);
        }
    }

    private static void addDeduped(List<BlockPos> out, BlockPos position) {
        if (position == null) {
            return;
        }
        BlockPos copy = position.immutable();
        if (out.isEmpty() || !out.get(out.size() - 1).equals(copy)) {
            out.add(copy);
        }
    }

    private static double pathLength(List<BlockPos> path) {
        double total = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            total += Math.sqrt(path.get(index - 1).distSqr(path.get(index)));
        }
        return total;
    }

    private static long dist2XZ(BlockPos left, BlockPos right) {
        long dx = (long) left.getX() - right.getX();
        long dz = (long) left.getZ() - right.getZ();
        return dx * dx + dz * dz;
    }

    private static List<UUID> dijkstra(UUID start, UUID end, Map<UUID, Set<RouteNeighbor>> adjacency) {
        PriorityQueue<RouteNode> open = new PriorityQueue<>(Comparator.comparingDouble(RouteNode::distance));
        Map<UUID, Double> dist = new HashMap<>();
        Map<UUID, UUID> prev = new HashMap<>();
        open.add(new RouteNode(start, 0.0D));
        dist.put(start, 0.0D);
        while (!open.isEmpty()) {
            RouteNode current = open.poll();
            if (current.nodeId().equals(end)) {
                break;
            }
            if (current.distance() > dist.getOrDefault(current.nodeId(), Double.MAX_VALUE)) {
                continue;
            }
            for (RouteNeighbor neighbor : adjacency.getOrDefault(current.nodeId(), Set.of())) {
                double candidate = current.distance() + neighbor.weight();
                if (candidate < dist.getOrDefault(neighbor.nodeId(), Double.MAX_VALUE)) {
                    dist.put(neighbor.nodeId(), candidate);
                    prev.put(neighbor.nodeId(), current.nodeId());
                    open.add(new RouteNode(neighbor.nodeId(), candidate));
                }
            }
        }
        if (!dist.containsKey(end)) {
            return List.of();
        }
        ArrayList<UUID> path = new ArrayList<>();
        UUID cursor = end;
        path.add(cursor);
        while (!cursor.equals(start)) {
            cursor = prev.get(cursor);
            if (cursor == null) {
                return List.of();
            }
            path.add(cursor);
        }
        java.util.Collections.reverse(path);
        return List.copyOf(path);
    }

    private static double length(List<BlockPos> path) {
        double total = 0.0D;
        for (int index = 1; index < path.size(); index++) {
            total += Math.sqrt(path.get(index - 1).distSqr(path.get(index)));
        }
        return total;
    }

    private static List<BlockPos> reversed(List<BlockPos> path) {
        ArrayList<BlockPos> out = new ArrayList<>(path);
        java.util.Collections.reverse(out);
        return out;
    }

    private static String edgeKey(UUID from, UUID to) {
        return from + "|" + to;
    }

    private record Graph(Map<UUID, RoadGraphNodeRecord> nodes,
                         Map<UUID, Set<RouteNeighbor>> adjacency,
                         Map<String, EdgeStep> edgeBetween) {
    }

    private record RouteNeighbor(UUID nodeId, double weight) {
    }

    private record RouteNode(UUID nodeId, double distance) {
    }

    private record EdgeStep(RoadGraphEdgeRecord edge, boolean forward) {
    }

    private record NodeHit(UUID nodeId, long distanceSqr) {
    }

    private record Attachment(BlockPos origin,
                              UUID nodeId,
                              RoadGraphEdgeRecord edge,
                              int centerlineIndex,
                              BlockPos roadPos) {
    }

    private record EndpointOption(UUID nodeId, List<BlockPos> path) {
    }

    private record RouteCandidate(List<BlockPos> path, double length) {
    }
}
