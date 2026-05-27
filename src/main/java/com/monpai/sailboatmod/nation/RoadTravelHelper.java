package com.monpai.sailboatmod.nation;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class RoadTravelHelper {
    private RoadTravelHelper() {
    }

    public static boolean shouldGrantRoadSpeed(BlockState standingState, BlockState belowState) {
        return isWalkableRoadSurface(standingState) || isWalkableRoadSurface(belowState);
    }

    public static boolean isWalkableRoadSurface(BlockState state) {
        if (state == null) {
            return false;
        }
        return state.is(Blocks.STONE_BRICK_SLAB)
                || state.is(Blocks.STONE_BRICK_STAIRS)
                || state.is(Blocks.STONE_BRICKS)
                || state.is(Blocks.SMOOTH_STONE_SLAB)
                || state.is(Blocks.SMOOTH_STONE)
                || state.is(Blocks.COBBLESTONE_SLAB)
                || state.is(Blocks.COBBLESTONE_STAIRS)
                || state.is(Blocks.COBBLESTONE)
                || state.is(Blocks.OAK_SLAB)
                || state.is(Blocks.OAK_STAIRS)
                || state.is(Blocks.OAK_PLANKS)
                || state.is(Blocks.SMOOTH_SANDSTONE_SLAB)
                || state.is(Blocks.SMOOTH_SANDSTONE_STAIRS)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.MUD_BRICK_SLAB)
                || state.is(Blocks.MUD_BRICK_STAIRS)
                || state.is(Blocks.MUD_BRICKS)
                || state.is(Blocks.SPRUCE_SLAB)
                || state.is(Blocks.SPRUCE_STAIRS)
                || state.is(Blocks.SPRUCE_PLANKS);
    }
}
