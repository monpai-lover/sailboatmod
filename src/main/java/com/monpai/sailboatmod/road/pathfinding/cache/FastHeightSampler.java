package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

public class FastHeightSampler {
    private final ServerLevel level;

    public FastHeightSampler(ServerLevel level) {
        this.level = level;
    }

    public int surfaceHeight(int x, int z) {
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1;
        while (y > level.getMinBuildHeight() && isVegetationOrDestructible(level.getBlockState(new BlockPos(x, y, z)))) {
            y--;
        }
        return y;
    }

    public int motionBlockingHeight(int x, int z) {
        return level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
    }

    public static boolean isVegetationOrDestructible(BlockState state) {
        if (state.isAir()) return false;
        if (state.is(BlockTags.LEAVES)) return true;
        if (state.is(BlockTags.LOGS)) return true;
        if (state.is(BlockTags.FLOWERS)) return true;
        if (state.is(BlockTags.SAPLINGS)) return true;
        if (state.is(BlockTags.TALL_FLOWERS)) return true;
        if (state.is(Blocks.TALL_GRASS) || state.is(Blocks.GRASS)) return true;
        if (state.is(Blocks.FERN) || state.is(Blocks.LARGE_FERN)) return true;
        if (state.is(Blocks.VINE) || state.is(Blocks.DEAD_BUSH)) return true;
        if (state.is(Blocks.SNOW)) return true;
        if (state.is(Blocks.BAMBOO) || state.is(Blocks.SUGAR_CANE)) return true;
        if (state.is(Blocks.SWEET_BERRY_BUSH) || state.is(Blocks.CACTUS)) return true;
        if (state.is(Blocks.MUSHROOM_STEM) || state.is(Blocks.RED_MUSHROOM_BLOCK) || state.is(Blocks.BROWN_MUSHROOM_BLOCK)) return true;
        if (state.is(Blocks.RED_MUSHROOM) || state.is(Blocks.BROWN_MUSHROOM)) return true;
        if (state.is(BlockTags.REPLACEABLE)) return true;
        return false;
    }
}
