package com.monpai.sailboatmod.resident.pathfinding.goal;

import net.minecraft.core.BlockPos;

/**
 * Represents a pathfinding goal (inspired by Maple pathfinding system)
 */
public interface Goal {
    /**
     * Calculate heuristic distance to this goal
     */
    double heuristic(BlockPos pos);

    /**
     * Check if position satisfies this goal
     */
    boolean isGoal(BlockPos pos);
}
