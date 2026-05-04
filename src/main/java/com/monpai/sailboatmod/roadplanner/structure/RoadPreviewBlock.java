package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.road.model.BuildPhase;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record RoadPreviewBlock(BlockPos pos, BlockState state, BuildPhase phase) {
    public RoadPreviewBlock {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        state = Objects.requireNonNull(state, "state");
        phase = Objects.requireNonNull(phase, "phase");
    }
}
