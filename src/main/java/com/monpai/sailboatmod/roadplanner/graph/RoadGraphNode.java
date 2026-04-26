package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.UUID;

public record RoadGraphNode(UUID nodeId, BlockPos position, Kind kind) {
    public RoadGraphNode {
        if (nodeId == null) {
            throw new IllegalArgumentException("nodeId cannot be null");
        }
        if (position == null) {
            throw new IllegalArgumentException("position cannot be null");
        }
        if (kind == null) {
            throw new IllegalArgumentException("kind cannot be null");
        }
    }

    public enum Kind {
        NORMAL,
        JUNCTION,
        TOWN_CONNECTION
    }
}
