package com.monpai.sailboatmod.route.terrain;

import net.minecraft.world.level.block.state.BlockState;

/** 一个地图像素的地表:高度(水面格为水面 Y)、地表方块、是否水、水深(非水为 0)。 */
public record SurfaceSample(int surfaceY, BlockState surface, boolean water, int waterDepth) {
    public SurfaceSample {
        if (waterDepth < 0) {
            throw new IllegalArgumentException("waterDepth must be >= 0: " + waterDepth);
        }
        if (!water && waterDepth != 0) {
            throw new IllegalArgumentException("dry land has no water depth: " + waterDepth);
        }
    }
}
