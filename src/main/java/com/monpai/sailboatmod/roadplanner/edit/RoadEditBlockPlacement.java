package com.monpai.sailboatmod.roadplanner.edit;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public record RoadEditBlockPlacement(String segmentId, BlockPos pos, BlockState roadState) {
    public RoadEditBlockPlacement {
        segmentId = segmentId == null ? "" : segmentId.trim().toLowerCase(java.util.Locale.ROOT);
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        roadState = roadState == null ? Blocks.AIR.defaultBlockState() : roadState;
    }
}
