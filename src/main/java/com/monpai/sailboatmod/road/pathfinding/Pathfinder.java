package com.monpai.sailboatmod.road.pathfinding;

import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

public interface Pathfinder {
    PathResult findPath(BlockPos start, BlockPos end, TerrainSamplingCache cache);
}
