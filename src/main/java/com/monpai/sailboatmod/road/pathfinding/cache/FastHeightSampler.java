package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class FastHeightSampler {
    private final ServerLevel level;

    public FastHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1;
        while (y > level.getMinBuildHeight() && RoadSurfaceHeuristics.isIgnoredSurfaceNoise(level.getBlockState(new BlockPos(x, y, z)))) {
            y--;
        }
        return y;
    }

    public int motionBlockingHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    public static boolean isVegetationOrDestructible(BlockState state) {
        return RoadSurfaceHeuristics.isIgnoredSurfaceNoise(state);
    }
}
