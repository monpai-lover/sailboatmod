package com.monpai.sailboatmod.roadplanner.graph;

import java.util.UUID;

public record RoadGraphEdge(UUID edgeId,
                            UUID fromNodeId,
                            UUID toNodeId,
                            RoadRouteMetadata metadata,
                            double lengthBlocks) {
    public RoadGraphEdge {
        if (edgeId == null) {
            throw new IllegalArgumentException("edgeId cannot be null");
        }
        if (fromNodeId == null) {
            throw new IllegalArgumentException("fromNodeId cannot be null");
        }
        if (toNodeId == null) {
            throw new IllegalArgumentException("toNodeId cannot be null");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata cannot be null");
        }
        if (lengthBlocks < 0) {
            throw new IllegalArgumentException("lengthBlocks cannot be negative");
        }
    }

    public boolean connects(UUID nodeId) {
        return fromNodeId.equals(nodeId) || toNodeId.equals(nodeId);
    }
}
