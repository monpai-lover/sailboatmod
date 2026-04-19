package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.*;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

public class GradientDescentPathfinder implements Pathfinder {
    private final PathfindingConfig config;
    public GradientDescentPathfinder(PathfindingConfig config) { this.config = config; }

    @Override
    public PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache) {
        throw new UnsupportedOperationException("Not yet implemented");
    }
}
