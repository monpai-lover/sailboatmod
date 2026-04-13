package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

public final class RoadTerrainAnalysisService {
    private final RoadTerrainSamplingCache cache;

    public RoadTerrainAnalysisService(RoadTerrainSamplingCache cache) {
        this.cache = cache;
    }

    public boolean requiresBridge(BlockPos pos) {
        return cache.sample(pos.getX(), pos.getZ()).water();
    }

    public int terrainPenalty(BlockPos pos) {
        int adjacentWater = 0;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                if (cache.sample(pos.getX() + dx, pos.getZ() + dz).water()) {
                    adjacentWater++;
                }
            }
        }
        return adjacentWater;
    }
}
