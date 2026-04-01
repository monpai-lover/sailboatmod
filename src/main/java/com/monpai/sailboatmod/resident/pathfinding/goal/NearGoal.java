package com.monpai.sailboatmod.resident.pathfinding.goal;

import net.minecraft.core.BlockPos;

/**
 * Goal to reach within a certain distance of a target position
 */
public class NearGoal implements Goal {
    private final BlockPos target;
    private final double range;

    public NearGoal(BlockPos target, double range) {
        this.target = target;
        this.range = range;
    }

    @Override
    public double heuristic(BlockPos pos) {
        double dist = Math.sqrt(pos.distSqr(target));
        return Math.max(0, dist - range);
    }

    @Override
    public boolean isGoal(BlockPos pos) {
        return pos.distSqr(target) <= range * range;
    }
}
