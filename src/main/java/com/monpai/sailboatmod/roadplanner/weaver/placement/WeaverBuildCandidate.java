package com.monpai.sailboatmod.roadplanner.weaver.placement;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Objects;

public record WeaverBuildCandidate(BlockPos pos, BlockState state, boolean visible) {
    public WeaverBuildCandidate {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        state = Objects.requireNonNull(state, "state");
    }
}
