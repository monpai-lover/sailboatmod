package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

import java.util.Objects;

public record RoadNode(BlockPos pos, long createdAtTick, NodeSource source) {
    public RoadNode {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        source = source == null ? NodeSource.MANUAL : source;
    }
}
