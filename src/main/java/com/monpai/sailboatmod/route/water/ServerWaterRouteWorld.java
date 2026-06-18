package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 水路寻路的世界采样:改用 {@link NoiseWaterSampler} 噪声海底高度判定「可航水面」,寻路阶段<b>零区块加载</b>,
 * 配合 (x,z) 列级缓存消除重复采样。这是修复 CHUNK_BUDGET_EXCEEDED 的核心——旧实现对每个新格强加载真实区块。
 *
 * <p>水面 y 统一取海平面(可航水域水面即海平面)。足迹/净空判定基于噪声可航性,不再逐层扫真实方块。
 * 玩家改动(人工运河/填海)由寻路出最终路径后的真实区块校验兜底,不在此采样阶段处理。
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
        // 船足迹内每一列都要是可航水面(噪声海底低于海平面足够深)
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
