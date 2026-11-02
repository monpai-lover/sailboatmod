package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class RoadSurfaceHeuristics {
    private RoadSurfaceHeuristics() {
    }

    public static boolean isIgnoredSurfaceNoise(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.SAPLINGS)
                || state.is(BlockTags.TALL_FLOWERS)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.VINE)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.SNOW)
                || state.is(Blocks.BAMBOO)
                || state.is(Blocks.SUGAR_CANE)
                || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CACTUS)
                || state.is(Blocks.MUSHROOM_STEM)
                || state.is(Blocks.RED_MUSHROOM_BLOCK)
                || state.is(Blocks.BROWN_MUSHROOM_BLOCK)
                || state.is(Blocks.RED_MUSHROOM)
                || state.is(Blocks.BROWN_MUSHROOM)
                || state.is(Blocks.BIG_DRIPLEAF)
                || state.is(Blocks.BIG_DRIPLEAF_STEM)
                || state.is(Blocks.SMALL_DRIPLEAF)
                || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT)
                || state.is(Blocks.HANGING_ROOTS)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(Blocks.SPORE_BLOSSOM)
                || state.is(Blocks.GLOW_LICHEN)
                || state.is(Blocks.SCULK_VEIN)
                || state.is(Blocks.MANGROVE_ROOTS)
                || state.is(Blocks.MANGROVE_PROPAGULE)
                || state.is(Blocks.AZALEA)
                || state.is(Blocks.FLOWERING_AZALEA)
                || state.is(Blocks.KELP)
                || state.is(Blocks.KELP_PLANT)
                || state.is(Blocks.SEAGRASS)
                || state.is(Blocks.TALL_SEAGRASS)
                || state.is(Blocks.SEA_PICKLE)
                || state.is(BlockTags.REPLACEABLE)
                || hasNaturalNoiseName(state);
    }

    private static boolean hasNaturalNoiseName(BlockState state) {
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_leaves")
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae");
    }

    public static boolean isProtectedFromNaturalCleanup(BlockState state) {
        return state != null && (state.is(Blocks.BEDROCK) || state.is(Blocks.BARRIER));
    }

    public static boolean isNaturalCleanupTarget(BlockState state) {
        return state != null
                && !state.isAir()
                && !isProtectedFromNaturalCleanup(state)
                && (isIgnoredSurfaceNoise(state) || state.is(BlockTags.REPLACEABLE));
    }

    public static boolean isRoadBearingSurface(BlockState state) {
        return state != null
                && !state.isAir()
                && state.getFluidState().isEmpty()
                && !isIgnoredSurfaceNoise(state);
    }
}
