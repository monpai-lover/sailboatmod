package com.monpai.sailboatmod.roadplanner.weaver.model;

import net.minecraft.core.BlockPos;

public record WeaverRoadSpan(BlockPos start, BlockPos end, WeaverSpanType type) {
}
