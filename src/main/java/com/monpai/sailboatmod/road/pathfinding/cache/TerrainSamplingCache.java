package com.monpai.sailboatmod.road.pathfinding.cache;

import com.monpai.sailboatmod.construction.RoadCoreExclusion;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import com.monpai.sailboatmod.road.config.PathfindingConfig;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class TerrainSamplingCache {
    private final ServerLevel level;
    private final FastHeightSampler fastSampler;
    private final AccurateHeightSampler accurateSampler;
    private final BiomeSource biomeSource;
    private final Climate.Sampler climateSampler;
    private final PathfindingConfig.SamplingPrecision precision;
    private final Set<Long> blockedColumns;
    private final Set<Long> allowedColumns;

    private final ConcurrentHashMap<Long, Integer> heightCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> waterSurfaceCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Boolean> waterCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> oceanFloorCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Holder<Biome>> biomeCache = new ConcurrentHashMap<>();

    public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision) {
        this(level, precision, Set.of());
    }

    public TerrainSamplingCache(ServerLevel level, PathfindingConfig.SamplingPrecision precision, Set<Long> blockedColumns) {
        this(level, precision, blockedColumns, Set.of());
    }

    public TerrainSamplingCache(ServerLevel level,
                                PathfindingConfig.SamplingPrecision precision,
                                Set<Long> blockedColumns,
                                Set<Long> allowedColumns) {
        this.level = level;
        this.fastSampler = new FastHeightSampler(level);
        this.accurateSampler = new AccurateHeightSampler(level);
        var chunkSource = level.getChunkSource();
        this.biomeSource = chunkSource.getGenerator().getBiomeSource();
        this.climateSampler = chunkSource.getGeneratorState().randomState().sampler();
        this.precision = precision;
        this.blockedColumns = blockedColumns == null || blockedColumns.isEmpty()
                ? Set.of()
                : Set.copyOf(blockedColumns);
        this.allowedColumns = allowedColumns == null || allowedColumns.isEmpty()
                ? Set.of()
                : Set.copyOf(allowedColumns);
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
            // 基于世界生成高度(getBaseHeight,不依赖区块加载,远端准):海底(OCEAN_FLOOR_WG)低于海平面
            // 足够多 → 水柱。WORLD_SURFACE_WG 与 OCEAN_FLOOR_WG 之差就是水深(WG 阶段水也算 surface 不算 floor)。
            int floor = getOceanFloor(x, z);
            int surface = getHeight(x, z);
            int sea = level.getSeaLevel();
            // 海底在海平面下,且地表(含水面)到海底有水柱 → 是水。
            return floor < sea - 1 && surface >= sea - 1;
        });
    }

    public int getWaterSurfaceY(int x, int z) {
        return waterSurfaceCache.computeIfAbsent(key(x, z), k -> {
            // WG 下:是水则水面=海平面,否则=海底(无区块依赖)。
            int floor = getOceanFloor(x, z);
            int surface = getHeight(x, z);
            int sea = level.getSeaLevel();
            boolean water = floor < sea - 1 && surface >= sea - 1;
            return water ? sea : floor;
        });
    }

    public int getOceanFloor(int x, int z) {
        return oceanFloorCache.computeIfAbsent(key(x, z), k -> accurateSampler.oceanFloor(x, z));
    }

    public Holder<Biome> getBiome(int x, int z) {
        // 噪声生物群系:问 biomeSource(不加载区块)。biome 坐标用 4 格 cell(>>2)。
        return biomeCache.computeIfAbsent(key(x, z), k ->
            biomeSource.getNoiseBiome(x >> 2, getHeight(x, z) >> 2, z >> 2, climateSampler)
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

    public boolean isBlocked(int x, int z) {
        long column = RoadCoreExclusion.columnKey(x, z);
        return blockedColumns.contains(column) || (!allowedColumns.isEmpty() && !allowedColumns.contains(column));
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

    public int motionBlockingHeight(int x, int z) {
        return fastSampler.motionBlockingHeight(x, z);
    }

    public void clear() {
        heightCache.clear();
        waterSurfaceCache.clear();
        waterCache.clear();
        oceanFloorCache.clear();
        biomeCache.clear();
    }
}
