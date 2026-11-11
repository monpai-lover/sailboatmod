package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

/** Lets the real road search run over a {@link SyntheticRoadWorld}. */
final class SyntheticRouteTerrain extends TerrainSamplingCache implements RoadRouteRefiner.Terrain {
    private final SyntheticRoadWorld world;

    SyntheticRouteTerrain(SyntheticRoadWorld world) {
        super(PathfindingConfig.SamplingPrecision.NORMAL);
        this.world = world;
    }

    @Override public int getHeight(int x, int z) { return world.terrainY(x, z) - 1; }
    @Override public int getOceanFloor(int x, int z) { return world.solidTop(x, z) - 1; }
    @Override public boolean isWater(int x, int z) { return world.solidTop(x, z) < SyntheticRoadWorld.SEA; }
    @Override public int getWaterSurfaceY(int x, int z) { return isWater(x, z) ? SyntheticRoadWorld.SEA : getHeight(x, z); }
    @Override public boolean isWaterBiome(int x, int z) { return isWater(x, z); }

    @Override public int height(int x, int z) { return getHeight(x, z); }
    @Override public boolean water(int x, int z) { return isWater(x, z); }
    @Override public boolean blocked(int x, int z) { return false; }
}
