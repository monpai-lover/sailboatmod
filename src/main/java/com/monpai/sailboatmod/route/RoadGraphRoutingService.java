package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
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
        UUID startNode = nearestNode(start, graph.nodes(), connectorRadius);
        UUID endNode = nearestNode(end, graph.nodes(), connectorRadius);
        if (startNode == null || endNode == null) {
            return List.of();
        }
        List<UUID> nodePath = dijkstra(startNode, endNode, graph.adjacency());
        if (nodePath.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<BlockPos> out = new LinkedHashSet<>();
        out.add(start.immutable());
        if (nodePath.size() == 1) {
            RoadGraphNodeRecord node = graph.nodes().get(nodePath.get(0));
            if (node != null) {
                out.add(node.pos());
            }
        }
        for (int index = 1; index < nodePath.size(); index++) {
            EdgeStep step = graph.edgeBetween().get(edgeKey(nodePath.get(index - 1), nodePath.get(index)));
            if (step != null) {
                out.addAll(step.forward() ? step.edge().centerline() : reversed(step.edge().centerline()));
            }
        }
        out.add(end.immutable());
        return List.copyOf(out);
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

    private static UUID nearestNode(BlockPos origin, Map<UUID, RoadGraphNodeRecord> nodes, int radius) {
        UUID best = null;
        long bestDistance = (long) radius * (long) radius;
        for (RoadGraphNodeRecord node : nodes.values()) {
            long distance = (long) origin.distSqr(node.pos());
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = node.nodeId();
            }
        }
        return best;
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
}
