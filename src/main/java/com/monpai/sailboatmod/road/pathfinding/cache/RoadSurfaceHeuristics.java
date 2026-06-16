package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

public final class RoadSurfaceHeuristics {
    private RoadSurfaceHeuristics() {
    }

    public static boolean isIgnoredSurfaceNoise(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        Block block = state.getBlock();
        return state.is(BlockTags.LEAVES)
                || state.is(BlockTags.LOGS)
                || block instanceof LeavesBlock
                || hasNaturalNoiseName(state)
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
                || state.is(Blocks.SNOW_BLOCK)
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
                || state.is(Blocks.LILY_PAD)
                || state.is(BlockTags.REPLACEABLE);
    }

    /**
     * 应「就地被路面替换」的贴地覆盖物（雪层/草本/苔藓地毯等）。
     * 与 {@link #isIgnoredSurfaceNoise} 的区别：这些覆盖物本身贴在真实地面上，路面应铺在它们的位置
     * （替换它们），而不是下探到它们之下 —— 否则路面会陷进地里。
     * <b>不含</b>树叶/原木/树苗/竹/甘蔗/仙人掌/水生植物（那些下面才是地面，或根本不该在路面上）。
     */
    public static boolean isReplaceableGroundCover(BlockState state) {
        if (state == null || state.isAir()) {
            return false;
        }
        return state.is(Blocks.SNOW)
                || state.is(Blocks.SNOW_BLOCK)
                || state.is(Blocks.GRASS)
                || state.is(Blocks.FERN)
                || state.is(Blocks.TALL_GRASS)
                || state.is(Blocks.LARGE_FERN)
                || state.is(Blocks.DEAD_BUSH)
                || state.is(Blocks.MOSS_CARPET)
                || state.is(BlockTags.FLOWERS)
                || state.is(BlockTags.TALL_FLOWERS);
    }

    private static boolean hasNaturalNoiseName(BlockState state) {
        if (state == null || state.getBlock() == null) {
            return false;
        }
        String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        return path.endsWith("_leaves")
                || path.endsWith("_log")
                || path.endsWith("_wood")
                || path.endsWith("_stem")
                || path.endsWith("_hyphae");
    }

    public static boolean isRoadBearingSurface(BlockState state) {
        return state != null
                && !state.isAir()
                && state.getFluidState().isEmpty()
                && !isIgnoredSurfaceNoise(state);
    }
}
