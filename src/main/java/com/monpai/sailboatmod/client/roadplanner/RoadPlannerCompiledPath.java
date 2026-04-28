package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public record RoadPlannerCompiledPath(List<BlockPos> centers,
                                      List<CompiledBlock> blocks,
                                      List<LightBlock> lights) {
    public RoadPlannerCompiledPath {
        centers = centers == null ? List.of() : centers.stream().map(BlockPos::immutable).toList();
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
        lights = lights == null ? List.of() : List.copyOf(lights);
    }

    public record CompiledBlock(BlockPos pos, BlockState state, RoadPlannerSegmentType segmentType) {
        public CompiledBlock {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            segmentType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        }
    }

    public record LightBlock(BlockPos pos, BlockState state) {
        public LightBlock {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }
}
