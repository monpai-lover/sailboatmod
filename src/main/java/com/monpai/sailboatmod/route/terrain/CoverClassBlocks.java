package com.monpai.sailboatmod.route.terrain;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ESA WorldCover 土地覆盖分类 → 代表方块(网页地图预测层远一点的层级用;配色仍走 MapBlockColors)。
 * 远层级逐格解析 Tellus 的 LOD 地表方块会逐点下载完整解析度数据,冷数据一张瓦片要十几分钟。
 */
public final class CoverClassBlocks {
    private CoverClassBlocks() {
    }

    public static BlockState blockFor(int coverClass) {
        return switch (coverClass) {
            case 10 -> Blocks.OAK_LEAVES.defaultBlockState();       // tree cover
            case 20 -> Blocks.MOSS_BLOCK.defaultBlockState();       // shrubland
            case 40 -> Blocks.HAY_BLOCK.defaultBlockState();        // cropland
            case 50 -> Blocks.STONE_BRICKS.defaultBlockState();     // built-up
            case 60 -> Blocks.SAND.defaultBlockState();             // bare / sparse vegetation
            case 70 -> Blocks.SNOW_BLOCK.defaultBlockState();       // snow and ice
            case 90 -> Blocks.MUD.defaultBlockState();              // herbaceous wetland
            case 95 -> Blocks.MANGROVE_LEAVES.defaultBlockState();  // mangroves
            case 100 -> Blocks.MOSS_BLOCK.defaultBlockState();      // moss and lichen
            default -> Blocks.GRASS_BLOCK.defaultBlockState();      // grassland (30) and unknown
        };
    }
}
