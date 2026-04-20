package com.monpai.sailboatmod.road.pathfinding.cache;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.monpai.sailboatmod.road.config.PathfindingConfig;

import java.util.concurrent.ConcurrentHashMap;

public class TerrainSamplingCache {
    private final ServerLevel level;
    private final FastHeightSampler fastSampler;
    private final AccurateHeightSampler accurateSampler;
    private final PathfindingConfig.SamplingPrecision precision;

    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> waterSurfaceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> waterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> oceanFloorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();

    public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision) {
        this.level = level;
        this.fastSampler = new FastHeightSampler(level);
        this.accurateSampler = new AccurateHeightSampler(level);
        this.precision = precision;
    }

    private static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public int getHeight(int x, int z) {
        return heightCache.computeIfAbsent(key(x, z), k -> {
            if (precision == PathfindingConfig.SamplingPrecision.NORMAL) {
                return fastSampler.surfaceHeight(x, z);
            }
            return accurateSampler.surfaceHeight(x, z);
        });
    }

    public boolean isWater(int x, int z) {
        return waterCache.computeIfAbsent(key(x, z), k -> {
            int waterSurfaceY = getWaterSurfaceY(x, z);
            return waterSurfaceY > getOceanFloor(x, z)
                    || (isWaterBiome(x, z) && level.getBlockState(new BlockPos(x, level.getSeaLevel(), z)).is(Blocks.WATER));
        });
    }

    public int getWaterSurfaceY(int x, int z) {
        return waterSurfaceCache.computeIfAbsent(key(x, z), k -> {
            int top = fastSampler.motionBlockingHeight(x, z) - 1;
            int floor = getOceanFloor(x, z);
            for (int y = Math.max(top, floor); y >= floor; y--) {
                BlockState state = level.getBlockState(new BlockPos(x, y, z));
                if (state.is(Blocks.WATER)) {
                    return y;
                }
            }
            return floor;
        });
    }

    public int getOceanFloor(int x, int z) {
        return oceanFloorCache.computeIfAbsent(key(x, z), k -> accurateSampler.oceanFloor(x, z));
    }

    public Holder<Biome> getBiome(int x, int z) {
        return biomeCache.computeIfAbsent(key(x, z), k ->
            level.getBiome(new BlockPos(x, getHeight(x, z), z))
        );
    }

    public boolean isWaterBiome(int x, int z) {
        Holder<Biome> biome = getBiome(x, z);
        return biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_RIVER);
    }

    public boolean isNearWater(int x, int z) {
        return isWater(x - 1, z) || isWater(x + 1, z)
            || isWater(x, z - 1) || isWater(x, z + 1);
    }

    public double terrainStability(int x, int z) {
        int center = getHeight(x, z);
        int n = getHeight(x, z - 1);
        int s = getHeight(x, z + 1);
        int e = getHeight(x + 1, z);
        int w = getHeight(x - 1, z);
        double mean = (n + s + e + w) / 4.0;
        double variance = ((n - mean) * (n - mean) + (s - mean) * (s - mean)
                + (e - mean) * (e - mean) + (w - mean) * (w - mean)) / 4.0;
        return Math.sqrt(variance);
    }

    public int getWaterDepth(int x, int z) {
        if (!isWater(x, z)) return 0;
        int surface = getWaterSurfaceY(x, z);
        int floor = getOceanFloor(x, z);
        return surface - floor;
    }

    public ServerLevel getLevel() { return level; }

    public void clear() {
        heightCache.clear();
        waterSurfaceCache.clear();
        waterCache.clear();
        oceanFloorCache.clear();
        biomeCache.clear();
    }
}
