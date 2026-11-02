package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.road.pathfinding.cache.RoadSurfaceHeuristics;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

public final class ConstructionStepExecutor {
    private ConstructionStepExecutor() {
    }

    public static void clearNaturalObstacles(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        clearIfNatural(level, pos);
        clearIfNatural(level, pos.above());
    }

    private static void clearIfNatural(ServerLevel level, BlockPos pos) {
        if (RoadSurfaceHeuristics.isNaturalCleanupTarget(level.getBlockState(pos))) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }
}
