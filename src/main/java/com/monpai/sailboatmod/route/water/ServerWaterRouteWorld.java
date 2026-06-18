package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 水路寻路的世界采样:用 {@link NoiseWaterSampler} 纯密度判定「可航水面」,寻路阶段<b>零区块加载</b>,
 * 配合 (x,z) 列级缓存消除重复采样。
 *
 * <p><b>2026-06 回退说明</b>:曾把采样源换成 {@link com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache}
 * (getBaseHeight 逐列烘焙 NoiseChunk)以修穿陆,但 getBaseHeight 比纯密度单点慢约 1000 倍,导致泊位扫描/寻路
 * 卡服+超时;降采样治标又让寻路看不见障碍直线穿陆。现退回纯密度(便宜、能跑通超远、不卡),穿陆精度问题改由
 * 寻路出路后的 {@link RealWaterVerifier}(用 NoiseChunkHeightSampler 整块烘焙、精确到方块、零加载)全程精确
 * 校验+局部绕开堵住——两阶段:寻路粗(纯密度)+ 校验精(NoiseChunk)。
 *
 * <p>水面 y 统一取海平面(可航水域水面即海平面)。足迹/净空判定基于噪声可航性,不逐层扫真实方块。
 * 玩家改动(人工运河/填海)由最终路径的真实区块校验兜底,不在此采样阶段处理。
 */
public final class ServerWaterRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    private final ServerLevel level;
    private final NoiseWaterSampler noise;
    private final Map<Long, WaterColumn> columnCache = new HashMap<>();

    public ServerWaterRouteWorld(ServerLevel level) {
        this.level = level;
        this.noise = level == null ? null : NoiseWaterSampler.create(level);
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
        // 船足迹内每一列都要是可航水面(噪声海底低于海平面足够深)。footprint 3×3 判船宽。
        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                if (!noise.isNavigableWater(x + dx, z + dz, minDepth)) {
                    return WaterColumn.blocked();
                }
            }
        }
        return WaterColumn.passable(new BlockPos(x, noise.seaLevel(), z), 0.0D);
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        // 噪声采样不加载区块,不再受区块预算约束。
        return true;
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
        return noise == null ? (level == null ? 63 : level.getSeaLevel()) : noise.seaLevel();
    }

    private static long packXZ(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
