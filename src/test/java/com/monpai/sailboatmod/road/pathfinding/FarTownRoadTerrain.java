package com.monpai.sailboatmod.road.pathfinding;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synthetic terrain for far town-to-town road tests: rolling hills plus an inland sea at
 * x in [seaMinX, seaMaxX], |z| < seaHalfWidth that forces a detour. Every uncached height/floor read
 * stands for one ChunkGenerator#getBaseHeight call in production; {@code sampleMicros} simulates its cost.
 */
public class FarTownRoadTerrain extends TerrainSamplingCache {
    public static final int SEA_LEVEL = 63;
    private final int seaMinX, seaMaxX, seaHalfWidth;
    private final long sampleMicros;
    public final AtomicLong baseHeightCalls = new AtomicLong();
    private final ConcurrentHashMap<Long, Integer> heights = new ConcurrentHashMap<>();

    public FarTownRoadTerrain(int seaMinX, int seaMaxX, int seaHalfWidth, long sampleMicros) {
        super(PathfindingConfig.SamplingPrecision.NORMAL);
        this.seaMinX = seaMinX;
        this.seaMaxX = seaMaxX;
        this.seaHalfWidth = seaHalfWidth;
        this.sampleMicros = sampleMicros;
    }

    private boolean sea(int x, int z) {
        return x >= seaMinX && x <= seaMaxX && Math.abs(z) < seaHalfWidth;
    }

    private int baseHeight(int x, int z) {
        baseHeightCalls.incrementAndGet();
        if (sampleMicros > 0) {
            long end = System.nanoTime() + sampleMicros * 1000L;
            while (System.nanoTime() < end) Thread.onSpinWait();
        }
        if (sea(x, z)) return 40;
        return (int) Math.round(72 + 14 * Math.sin(x / 310.0) * Math.cos(z / 260.0) + 5 * Math.sin(x / 57.0 + z / 91.0));
    }

    @Override public int getHeight(int x, int z) {
        return heights.computeIfAbsent(((long) x << 32) | (z & 0xFFFFFFFFL), k -> {
            int h = baseHeight(x, z);
            return sea(x, z) ? SEA_LEVEL - 1 : h;
        });
    }
    @Override public int getOceanFloor(int x, int z) { return sea(x, z) ? 40 : getHeight(x, z); }
    @Override public boolean isWater(int x, int z) { getHeight(x, z); return sea(x, z); }
    @Override public int getWaterSurfaceY(int x, int z) { return sea(x, z) ? SEA_LEVEL : getHeight(x, z); }
    @Override public boolean isWaterBiome(int x, int z) { return sea(x, z); }
}
