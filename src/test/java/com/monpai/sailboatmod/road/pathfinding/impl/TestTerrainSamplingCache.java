package com.monpai.sailboatmod.road.pathfinding.impl;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

import java.util.Set;

final class TestTerrainSamplingCache extends TerrainSamplingCache {
    @FunctionalInterface
    interface HeightMap {
        int height(int x, int z);
    }

    @FunctionalInterface
    interface WaterMap {
        boolean water(int x, int z);
    }

    private final HeightMap heights;
    private final WaterMap water;
    private final Set<Long> blocked;

    TestTerrainSamplingCache(HeightMap heights) {
        this(heights, (x, z) -> false, Set.of());
    }

    TestTerrainSamplingCache(HeightMap heights, WaterMap water, Set<Long> blocked) {
        super(null, PathfindingConfig.SamplingPrecision.NORMAL);
        this.heights = heights;
        this.water = water;
        this.blocked = blocked == null ? Set.of() : Set.copyOf(blocked);
    }

    @Override
    public int getHeight(int x, int z) {
        return heights.height(x, z);
    }

    @Override
    public boolean isWater(int x, int z) {
        return water.water(x, z);
    }

    @Override
    public boolean isWaterBiome(int x, int z) {
        return water.water(x, z);
    }

    @Override
    public boolean isNearWater(int x, int z) {
        return water.water(x - 1, z) || water.water(x + 1, z)
                || water.water(x, z - 1) || water.water(x, z + 1);
    }

    @Override
    public int getWaterDepth(int x, int z) {
        return water.water(x, z) ? 4 : 0;
    }

    @Override
    public boolean isBlocked(int x, int z) {
        return blocked.contains(RoadCoreExclusion.columnKey(x, z));
    }

    @Override
    public double terrainStability(int x, int z) {
        return 0;
    }
}
