package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** The block palette a road is built from, derived from the player's chosen surface. */
record RoadCorridorMaterials(BlockState surface,
                             BlockState slab,
                             BlockState structure,
                             BlockState pier,
                             BlockState railing,
                             BlockState fill,
                             BlockState wetFill) {

    static RoadCorridorMaterials of(RoadPlannerBuildSettings settings) {
        BlockState structure;
        BlockState pier;
        BlockState railing;
        switch (settings.materialPreset()) {
            case "oak_planks" -> {
                structure = Blocks.OAK_PLANKS.defaultBlockState();
                pier = Blocks.OAK_LOG.defaultBlockState();
                railing = Blocks.OAK_FENCE.defaultBlockState();
            }
            case "spruce_planks" -> {
                structure = Blocks.SPRUCE_PLANKS.defaultBlockState();
                pier = Blocks.SPRUCE_LOG.defaultBlockState();
                railing = Blocks.SPRUCE_FENCE.defaultBlockState();
            }
            case "cobblestone" -> {
                structure = Blocks.STONE_BRICKS.defaultBlockState();
                pier = Blocks.STONE_BRICKS.defaultBlockState();
                railing = Blocks.COBBLESTONE_WALL.defaultBlockState();
            }
            default -> {
                structure = Blocks.STONE_BRICKS.defaultBlockState();
                pier = Blocks.STONE_BRICKS.defaultBlockState();
                railing = Blocks.STONE_BRICK_WALL.defaultBlockState();
            }
        }
        return new RoadCorridorMaterials(settings.surfaceState(), settings.slabBottomState(), structure, pier, railing,
                Blocks.DIRT.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState());
    }

    /** Ground that may be copied onto a graded verge. Anything else is replaced with grass. */
    static boolean isNaturalGround(BlockState state) {
        if (state == null || state.isAir() || !state.getFluidState().isEmpty()) {
            return false;
        }
        return state.is(BlockTags.DIRT)
                || state.is(BlockTags.SAND)
                || state.is(BlockTags.BASE_STONE_OVERWORLD)
                || state.is(BlockTags.TERRACOTTA)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SANDSTONE)
                || state.is(Blocks.RED_SANDSTONE)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.ICE)
                || state.is(Blocks.PACKED_ICE);
    }
}
