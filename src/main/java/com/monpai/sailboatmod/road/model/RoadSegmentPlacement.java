package com.monpai.sailboatmod.road.model;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RoadSegmentPlacement(
    BlockPos center,
    int segmentIndex,
    List<BlockPos> positions,
    boolean bridge
) {
    public RoadSegmentPlacement {
        center = center.immutable();
        positions = positions == null ? List.of() : List.copyOf(positions);
    }
}
