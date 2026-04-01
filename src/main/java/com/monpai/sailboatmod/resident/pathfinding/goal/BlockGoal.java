package com.monpai.sailboatmod.resident.pathfinding.goal;

import net.minecraft.core.BlockPos;

/**
 * Goal to reach a specific block position
 */
public class BlockGoal implements Goal {
    private final BlockPos target;

    public BlockGoal(BlockPos target) {
        this.target = target;
    }

    @Override
    public double heuristic(BlockPos pos) {
        return Math.sqrt(pos.distSqr(target));
    }

    @Override
    public boolean isGoal(BlockPos pos) {
        return pos.equals(target);
    }
}
