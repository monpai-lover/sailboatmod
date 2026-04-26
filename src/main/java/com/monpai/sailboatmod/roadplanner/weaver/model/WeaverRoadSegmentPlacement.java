package com.monpai.sailboatmod.roadplanner.weaver.model;

import net.minecraft.core.BlockPos;

import java.util.List;

public record WeaverRoadSegmentPlacement(BlockPos middlePos, List<BlockPos> positions) {
    public WeaverRoadSegmentPlacement {
        positions = List.copyOf(positions);
    }
}
