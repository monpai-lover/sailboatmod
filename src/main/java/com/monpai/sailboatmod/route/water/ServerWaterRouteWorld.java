package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;

/**
 * 水路寻路的世界采样:<b>粗细结合</b>(2026-06)——开阔海域用 {@link NoiseWaterSampler}(纯密度,微秒级、够用),
 * 岸边/起终点/浅滩用 {@link NoiseChunkHeightSampler}(整块烘焙真方块插值,精确到方块,绕陆不出错)。
 *
 * <p><b>为什么粗细结合</b>:纯密度便宜但判障粗(水平4格对齐+垂直8格步长+只判海底深度不判地表),在岸边把陆地
 * 误判成水 → 航线直插上岸(实测穿陆)。但开阔海中段一大片纯密度完全够用(随便走都不穿陆)、且飞快。所以按
 * 离岸/复杂度自适应:明确深水信纯密度,疑似岸边升级 NoiseChunk 精判(偏精门槛,宁可多精判,保证岸边不穿陆)。
 *
 * <p><b>开阔海深度软代价</b>:粗采样本就算了海底深度,顺便做成软偏好——浅水稍贵、深水便宜,让 A* 在等可航区里
 * 偏向深水、走出贴合海底的自然折线(而非笔直一条线)。权重小,不绕大远。
 *
 * <p>采样的是世界生成原始地形(玩家挖的运河/填海不反映);最终路径由 {@link RealWaterVerifier} 兜底。
 */
public final class ServerWaterRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    /** 海底深度 ≥ 此值(格)且邻格皆水视为「明确深水/开阔海」,信纯密度;否则疑似岸边升级 NoiseChunk。 */
    private static final int DEEP_MARGIN = 6;
    /** 陆地 biome(含去 IS_RIVER 后的 river)分支的「明确深水」短路门槛:比海洋严(river 误深风险更高),取 8。
     *  宽河口/深内海(深 ≥ 8 格)信纯密度不烘焙 NoiseChunk;否则升级精判,防大片 river 全精判爆慢。 */
    private static final int LAND_BIOME_DEEP_MARGIN = 8;
    /** 开阔海深度软代价权重:浅水每浅 1 格加这么多代价,让 A* 偏向深水走折线。小,只够软偏好不绕远。 */
    private static final double DEPTH_COST_WEIGHT = 0.15D;
    /** 浅水软代价封顶,避免接近岸边的浅水代价过大反而绕远(绕陆由精判保证,不靠这个)。 */
    private static final double MAX_DEPTH_COST = 1.5D;

    private final ServerLevel level;
    private final NoiseWaterSampler noise;             // 粗:纯密度,开阔海
    private final NoiseChunkHeightSampler precise;     // 精:整块烘焙,岸边(null=非噪声生成器)
    private final int seaLevel;
    private final BiomeSource biomeSource;             // biome 门槛:判海洋/河流 biome(零加载、稳定)
    private final Climate.Sampler climateSampler;
    private final Map<Long, WaterColumn> columnCache = new HashMap<>();
    private final Map<Long, Boolean> waterBiomeCache = new HashMap<>();

    public ServerWaterRouteWorld(ServerLevel level) {
        this.level = level;
        this.noise = level == null ? null : NoiseWaterSampler.create(level);
        this.precise = level == null ? null : NoiseChunkHeightSampler.createOrNull(level);
        this.seaLevel = level == null ? 63 : level.getSeaLevel();
        if (level != null) {
            var chunkSource = level.getChunkSource();
            this.biomeSource = chunkSource.getGenerator().getBiomeSource();
            this.climateSampler = chunkSource.getGeneratorState().randomState().sampler();
        } else {
            this.biomeSource = null;
            this.climateSampler = null;
        }
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        if (level == null || noise == null) {
            return WaterColumn.blocked();
        }
        long key = packXZ(x, z);
        WaterColumn cached = columnCache.get(key);
        if (cached != null) {
            return cached;
        }
        WaterColumn result = computeColumn(x, z, policy);
        columnCache.put(key, result);
        return result;
    }

    private WaterColumn computeColumn(int x, int z, WaterRoutePolicy policy) {
        WaterRoutePolicy effective = policy == null ? WaterRoutePolicy.defaults() : policy;
        int halfWidth = Math.max(0, effective.boatHalfWidth());
        int minDepth = Math.max(1, effective.clearanceHeight());

        // ---- biome 门槛(核心):biome 不依赖密度高度、稳定可靠,作首道升级判据 ----
        // 陆地 biome(含去 IS_RIVER 后的 river)→ 绝不直信纯密度(纯密度 8 格步长会把窄陆误判成深水跳过精判
        //   →直线穿陆),但也不无脑全精判(大片 river 全烘焙 NoiseChunk 会爆慢):先纯密度探深度,中心明确深水
        //   (≥LAND_BIOME_DEEP_MARGIN)且 footprint 粗判全可航 → 信纯密度(宽河口/深内海,不烘焙);否则升级精判。
        if (!isWaterBiome(x, z)) {
            int landFloor = noise.seaFloorHeight(x, z);
            boolean landClearlyDeep = (seaLevel - landFloor) >= LAND_BIOME_DEEP_MARGIN;
            if (landClearlyDeep && coarseFootprintAllWater(x, z, halfWidth, minDepth)) {
                return WaterColumn.passable(new BlockPos(x, seaLevel, z), 0.0D); // 宽河口/深内海:纯密度足够,不烘焙
            }
            if (!preciseFootprintAllWater(x, z, halfWidth, minDepth)) {
                return WaterColumn.blocked(); // 确实是陆/窄陆 → 绕开
            }
            return WaterColumn.passable(new BlockPos(x, seaLevel, z), 0.0D); // 深入陆地的真河道/海湾:精判放行
        }

        // ---- 水 biome(海洋/河流):走粗细结合 ----
        // seaFloorHeight 是纯密度首个固体 y;深度 = 海平面 - 它。
        int centerFloor = noise.seaFloorHeight(x, z);
        int centerDepth = seaLevel - centerFloor;
        boolean centerClearlyDeep = centerDepth >= DEEP_MARGIN;

        // 偏精门槛:中心明确深水 且 footprint 邻格纯密度也都明确可航 → 信纯密度(开阔海);否则升级精判。
        boolean useCoarse = centerClearlyDeep && coarseFootprintAllWater(x, z, halfWidth, minDepth);

        boolean navigable;
        if (useCoarse) {
            navigable = true; // 已确认开阔海可航
        } else {
            // 疑似岸边/浅滩:footprint 逐格用 NoiseChunk 精判(任一格不可航则整格不可航,船宽边界严)。
            navigable = preciseFootprintAllWater(x, z, halfWidth, minDepth);
        }
        if (!navigable) {
            return WaterColumn.blocked();
        }

        // ---- 开阔海深度软代价:浅水稍贵让 A* 偏向深水走折线(岸边精判格不另加,绕陆为主)----
        double extraCost = 0.0D;
        if (useCoarse) {
            int shallowBy = (seaLevel - DEEP_MARGIN) - centerFloor; // 海底比"深水线"浅多少(>0 才算浅)
            if (shallowBy > 0) {
                extraCost = Math.min(MAX_DEPTH_COST, shallowBy * DEPTH_COST_WEIGHT);
            }
        }
        return WaterColumn.passable(new BlockPos(x, seaLevel, z), extraCost);
    }

    /**
     * 该格是否水 biome(海洋/河流/深海)。biome 由噪声 biomeSource 直接查(零区块加载),不依赖密度高度,
     * 比纯密度判障稳定可靠——纯密度 8 格步长会把陆地误判成深水,biome 不会把陆地标成水 biome。
     * 4 格对齐 + 缓存(biome 本就 4×4 cell 精度)。非噪声生成器回退 true(不靠 biome 门槛,走后续粗细判)。
     */
    private boolean isWaterBiome(int x, int z) {
        if (biomeSource == null || climateSampler == null) {
            return true; // 无 biome 源:不启用 biome 门槛,交给后续粗细结合
        }
        int ax = (x >> 2) << 2;
        int az = (z >> 2) << 2;
        long key = packXZ(ax, az);
        Boolean cached = waterBiomeCache.get(key);
        if (cached != null) {
            return cached;
        }
        Holder<Biome> biome = biomeSource.getNoiseBiome(x >> 2, seaLevel >> 2, z >> 2, climateSampler);
        // 2026-06 去 IS_RIVER:河流/河口/沿海低地被纯密度误判可航直插穿陆(实测中段斜切群岛/大陆)。
        // 只信海洋/深海走纯密度;river 走陆地 biome 分支强制精判(真河道仍放行,夹缝窄陆 blocked 绕开)。
        boolean water = biome.is(BiomeTags.IS_OCEAN) || biome.is(BiomeTags.IS_DEEP_OCEAN);
        waterBiomeCache.put(key, water);
        return water;
    }

    /** footprint 内每格纯密度都判可航水(粗,便宜)。 */
    private boolean coarseFootprintAllWater(int x, int z, int halfWidth, int minDepth) {
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (!noise.isNavigableWater(x + dx, z + dz, minDepth)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** footprint 内每格 NoiseChunk 精判都可航水(精,岸边用)。precise 不可用时回退纯密度。 */
    private boolean preciseFootprintAllWater(int x, int z, int halfWidth, int minDepth) {
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (!preciseNavigable(x + dx, z + dz, minDepth)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 单格 NoiseChunk 精确可航判定:海床(OCEAN_FLOOR_WG)低于海平面足够深(有水柱够吃水)且
     * 地表/水面(WORLD_SURFACE_WG)不高出海平面(高出=陆地冒出水面)。精确到方块。
     */
    private boolean preciseNavigable(int x, int z, int minDepth) {
        if (precise == null) {
            return noise.isNavigableWater(x, z, minDepth); // 非噪声生成器:回退纯密度
        }
        int floor = precise.oceanFloorWg(x, z) - 1;     // 实心海床顶
        int surface = precise.worldSurfaceWg(x, z) - 1;  // 地表/水面顶
        return floor <= seaLevel - minDepth && surface <= seaLevel;
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        return true; // 采样不加载区块
    }

    @Override
    public int consumedChunkLoads() {
        return 0;
    }

    @Override
    public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
        return sample(x, z, policy).passable();
    }

    @Override
    public int waterSurfaceY(int x, int z) {
        return seaLevel; // 可航水域水面即海平面
    }

    private static long packXZ(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
