package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class RoadNetworkGraph {
    private final Map<UUID, RoadGraphNode> nodes = new LinkedHashMap<>();
    private final Map<UUID, RoadGraphEdge> edges = new LinkedHashMap<>();

    public RoadGraphNode addNode(BlockPos position, RoadGraphNode.Kind kind) {
        RoadGraphNode node = new RoadGraphNode(UUID.randomUUID(), position, kind);
        nodes.put(node.nodeId(), node);
        return node;
    }

    public RoadGraphEdge addEdge(UUID fromNodeId, UUID toNodeId, RoadRouteMetadata metadata) {
        RoadGraphNode from = requireNode(fromNodeId);
        RoadGraphNode to = requireNode(toNodeId);
        RoadGraphEdge edge = new RoadGraphEdge(UUID.randomUUID(), fromNodeId, toNodeId, metadata, length(from.position(), to.position()));
        edges.put(edge.edgeId(), edge);
        return edge;
    }

    public List<RoadGraphEdge> edgesConnectedTo(UUID nodeId) {
        requireNode(nodeId);
        return edges.values().stream()
                .filter(edge -> edge.connects(nodeId))
                .toList();
    }

    public Optional<RoadGraphNode> node(UUID nodeId) {
        return Optional.ofNullable(nodes.get(nodeId));
    }

    public Collection<RoadGraphEdge> edges() {
        return List.copyOf(edges.values());
    }

    private RoadGraphNode requireNode(UUID nodeId) {
        RoadGraphNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("Unknown road graph node: " + nodeId);
        }
        return node;
    }

    private double length(BlockPos from, BlockPos to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
