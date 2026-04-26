package com.monpai.sailboatmod.roadplanner.service;

import net.minecraft.core.BlockPos;

import java.util.Objects;

public class RoadPlannerDestinationService {
    public BlockPos fromCoordinates(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    public BlockPos fromBlock(BlockPos blockPos) {
        return Objects.requireNonNull(blockPos, "blockPos").immutable();
    }

    public BlockPos fromCurrentPlayerPosition(BlockPos playerPos) {
        return Objects.requireNonNull(playerPos, "playerPos").immutable();
    }
}
