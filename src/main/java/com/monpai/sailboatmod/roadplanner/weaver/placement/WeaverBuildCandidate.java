package com.monpai.sailboatmod.roadplanner.weaver.placement;

import com.monpai.sailboatmod.roadplanner.build.RoadBuildStep;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record WeaverBuildCandidate(BlockPos pos, BlockState state, boolean visible, RoadBuildStep.Phase phase) {
    public WeaverBuildCandidate(BlockPos pos, BlockState state, boolean visible) {
        this(pos, state, visible, RoadBuildStep.Phase.ROAD_SURFACE);
    }

    public WeaverBuildCandidate {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        state = Objects.requireNonNull(state, "state");
        phase = phase == null ? RoadBuildStep.Phase.ROAD_SURFACE : phase;
    }
}
