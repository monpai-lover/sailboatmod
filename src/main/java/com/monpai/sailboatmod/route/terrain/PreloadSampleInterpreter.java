package com.monpai.sailboatmod.route.terrain;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;
import java.util.function.Supplier;

/**
 * 把 Tellus 预载地形取样(高度、ESA WorldCover 分类、陆/海、陆地遮罩是否已知)解释成地图像素。
 * <ul>
 *   <li>陆地遮罩未知 = 数据没下载成功:Tellus 会静默退回预设值(平坦草地),所以当作取样失败,不让这种瓦片被存档。</li>
 *   <li>海:水面取海平面,水深 = 海平面 − 海床;WorldCover 80(永久水体)在陆地上当浅水。</li>
 *   <li>陆地方块:层级 0(每像素 1 格)用 LOD 地表方块;更远层级按土地覆盖分类取代表方块(LOD 逐点下载太慢)。</li>
 * </ul>
 */
public final class PreloadSampleInterpreter {
    public static final int COVER_PERMANENT_WATER = 80;

    private PreloadSampleInterpreter() {
    }

    public static Optional<SurfaceSample> interpret(int terrainHeight, int coverClass, boolean land, boolean landMaskKnown,
                                                    int seaLevel, int blocksPerSample, Supplier<BlockState> lodSurface) {
        if (!landMaskKnown) {
            return Optional.empty();
        }
        if (!land) {
            return Optional.of(new SurfaceSample(seaLevel, Blocks.WATER.defaultBlockState(), true,
                    Math.max(1, seaLevel - terrainHeight)));
        }
        if (coverClass == COVER_PERMANENT_WATER) {
            return Optional.of(new SurfaceSample(terrainHeight, Blocks.WATER.defaultBlockState(), true, 1));
        }
        BlockState top = blocksPerSample <= 1 ? lodSurface.get() : CoverClassBlocks.blockFor(coverClass);
        return Optional.of(new SurfaceSample(terrainHeight, top, false, 0));
    }
}
