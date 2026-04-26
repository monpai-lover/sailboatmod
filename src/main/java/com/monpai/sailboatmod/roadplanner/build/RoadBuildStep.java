package com.monpai.sailboatmod.roadplanner.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

public record RoadBuildStep(UUID edgeId,
                            BlockPos pos,
                            BlockState state,
                            boolean visible,
                            Phase phase,
                            boolean rollbackRequired) {
    public RoadBuildStep {
        if (edgeId == null) {
            throw new IllegalArgumentException("edgeId cannot be null");
        }
        if (pos == null) {
            throw new IllegalArgumentException("pos cannot be null");
        }
        pos = pos.immutable();
        if (state == null) {
            throw new IllegalArgumentException("state cannot be null");
        }
        if (phase == null) {
            throw new IllegalArgumentException("phase cannot be null");
        }
    }

    public enum Phase {
        CLEAR_TO_SKY,
        TERRAIN_FLATTEN,
        ROAD_SURFACE,
        ROAD_EDGE,
        BRIDGE_DECK,
        TUNNEL
    }
}
