package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;

public class AccurateHeightSampler {
    private final ServerLevel level;

    public AccurateHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        for (int y = level.getMaxBuildHeight(); y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.getFluidState().is(Fluids.WATER)
                    && !state.getFluidState().is(Fluids.FLOWING_WATER)
                    && !FastHeightSampler.isVegetationOrDestructible(state)) {
                return y;
            }
        }
        return level.getMinBuildHeight();
    }

    public int oceanFloor(int x, int z) {
        for (int y = level.getMaxBuildHeight(); y >= level.getMinBuildHeight(); y--) {
            BlockState state = level.getBlockState(new BlockPos(x, y, z));
            if (!state.isAir() && !state.getFluidState().isSource()) {
                return y;
            }
        }
        return level.getMinBuildHeight();
    }
}
