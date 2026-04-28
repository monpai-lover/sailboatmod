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

    public Optional<RoadGraphEdge> edge(UUID edgeId) {
        return Optional.ofNullable(edges.get(edgeId));
    }

    public Optional<RoadGraphEdge> renameEdge(UUID edgeId, String roadName) {
        RoadGraphEdge edge = edges.get(edgeId);
        if (edge == null) {
            return Optional.empty();
        }
        RoadRouteMetadata metadata = edge.metadata();
        RoadGraphEdge updated = new RoadGraphEdge(
                edge.edgeId(),
                edge.fromNodeId(),
                edge.toNodeId(),
                new RoadRouteMetadata(
                        roadName,
                        metadata.fromTownName(),
                        metadata.toTownName(),
                        metadata.creatorId(),
                        metadata.createdAtGameTime(),
                        metadata.width(),
                        metadata.type(),
                        metadata.status()
                ),
                edge.lengthBlocks()
        );
        edges.put(edgeId, updated);
        return Optional.of(updated);
    }





    public Optional<RoadGraphEdge> updateEdgeType(UUID edgeId, com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType type) {
        RoadGraphEdge edge = edges.get(edgeId);
        if (edge == null || type == null) {
            return Optional.empty();
        }
        RoadRouteMetadata metadata = edge.metadata();
        RoadGraphEdge updated = new RoadGraphEdge(
                edge.edgeId(),
                edge.fromNodeId(),
                edge.toNodeId(),
                new RoadRouteMetadata(
                        metadata.roadName(),
                        metadata.fromTownName(),
                        metadata.toTownName(),
                        metadata.creatorId(),
                        metadata.createdAtGameTime(),
                        metadata.width(),
                        type,
                        metadata.status()
                ),
                edge.lengthBlocks()
        );
        edges.put(edgeId, updated);
        return Optional.of(updated);
    }

    public Optional<RoadGraphEdge> removeEdge(UUID edgeId) {
        if (edgeId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(edges.remove(edgeId));
    }

    public int removeBranchFromEdge(UUID edgeId) {
        RoadGraphEdge root = edges.get(edgeId);
        if (root == null) {
            return 0;
        }
        UUID branchNodeId = root.toNodeId();
        List<UUID> toRemove = new ArrayList<>();
        collectBranchEdges(branchNodeId, root.fromNodeId(), toRemove);
        for (UUID removeId : toRemove) {
            edges.remove(removeId);
        }
        return toRemove.size();
    }

    private void collectBranchEdges(UUID nodeId, UUID previousNodeId, List<UUID> edgeIds) {
        for (RoadGraphEdge edge : new ArrayList<>(edges.values())) {
            if (!edge.connects(nodeId) || edge.connects(previousNodeId)) {
                continue;
            }
            edgeIds.add(edge.edgeId());
            UUID next = edge.fromNodeId().equals(nodeId) ? edge.toNodeId() : edge.fromNodeId();
            collectBranchEdges(next, nodeId, edgeIds);
        }
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
