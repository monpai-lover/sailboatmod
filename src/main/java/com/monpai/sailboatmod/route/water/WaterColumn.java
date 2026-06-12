package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

public record WaterColumn(boolean passable, BlockPos surfacePos, double extraCost) {
    public static WaterColumn passable(BlockPos surfacePos, double extraCost) {
        return new WaterColumn(true, surfacePos, Math.max(0.0D, extraCost));
    }

    public static WaterColumn blocked() {
        return new WaterColumn(false, null, 0.0D);
    }
}
