package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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
    /** 开阔海深度软代价权重:浅水每浅 1 格加这么多代价,让 A* 偏向深水走折线。小,只够软偏好不绕远。 */
    private static final double DEPTH_COST_WEIGHT = 0.15D;
    /** 浅水软代价封顶,避免接近岸边的浅水代价过大反而绕远(绕陆由精判保证,不靠这个)。 */
    private static final double MAX_DEPTH_COST = 1.5D;

    private final ServerLevel level;
    private final NoiseWaterSampler noise;             // 粗:纯密度,开阔海
    private final NoiseChunkHeightSampler precise;     // 精:整块烘焙,岸边(null=非噪声生成器)
    private final int seaLevel;
    private final Map<Long, WaterColumn> columnCache = new HashMap<>();

    public ServerWaterRouteWorld(ServerLevel level) {
        this.level = level;
        this.noise = level == null ? null : NoiseWaterSampler.create(level);
        this.precise = level == null ? null : NoiseChunkHeightSampler.createOrNull(level);
        this.seaLevel = level == null ? 63 : level.getSeaLevel();
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

        // ---- 粗细结合:先看中心点纯密度,判明确深水还是疑似岸边 ----
        // seaFloorHeight 是纯密度首个固体 y(开阔海=海底,陆地=地表);深度 = 海平面 - 它。
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
