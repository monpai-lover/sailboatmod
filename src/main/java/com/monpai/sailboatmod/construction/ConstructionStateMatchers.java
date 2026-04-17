package com.monpai.sailboatmod.construction;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class ConstructionStateMatchers {
    private ConstructionStateMatchers() {
    }

    static boolean isNaturalCleanup(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(BlockTags.LOGS)
                || state.is(BlockTags.LEAVES)
                || state.getBlock() instanceof LeavesBlock
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae")
                || path.endsWith("_leaves")
                || state.is(Blocks.VINE)
                || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)
                || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.OAK_SAPLING)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.BAMBOO_SAPLING)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.LILY_PAD);
    }

    static boolean isEquivalentRoadDeck(BlockState existing, BlockState target) {
        if (existing == null || target == null) {
            return false;
        }
        return existing.is(Blocks.STONE_BRICKS) && target.is(Blocks.STONE_BRICK_SLAB);
    }

    static boolean isRoadReplaceableTerrain(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return state.is(Blocks.GRASS_BLOCK)
                || state.is(Blocks.DIRT)
                || state.is(Blocks.COARSE_DIRT)
                || state.is(Blocks.PODZOL)
                || state.is(Blocks.MYCELIUM)
                || state.is(Blocks.STONE)
                || state.is(Blocks.ANDESITE)
                || state.is(Blocks.GRANITE)
                || state.is(Blocks.DIORITE)
                || state.is(Blocks.GRAVEL)
                || state.is(Blocks.SAND)
                || state.is(Blocks.RED_SAND)
                || state.is(Blocks.CLAY)
                || state.is(Blocks.MUD)
                || path.endsWith("_stone")
                || path.endsWith("_dirt")
                || path.endsWith("_sand")
                || path.endsWith("_gravel");
    }
}
