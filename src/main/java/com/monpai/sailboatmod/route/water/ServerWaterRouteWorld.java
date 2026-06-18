package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.config.PathfindingConfig;
import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.Map;

/**
 * 水路寻路的世界采样。<b>2026-06 借鉴 RoadWeaver:改用 {@link TerrainSamplingCache} 的世界生成高度采样
 * ({@code getBaseHeight} 走 WORLD_SURFACE_WG / OCEAN_FLOOR_WG)判定「可航水面」</b>,取代旧的
 * {@link NoiseWaterSampler} 纯密度采样。寻路阶段仍<b>零区块加载</b>,配合列级缓存消除重复采样。
 *
 * <p><b>为何换:</b>旧的 NoiseWaterSampler 用 {@code initialDensityWithoutJaggedness().compute(单点)} 纯密度
 * 找海底,在海岸/河口过渡带把陆地误判成空(密度低)→ 海底返回 minY=-64 → 任何地方都判成「深水可航」
 * → 航线直射大陆腹地、船卡死。{@link TerrainSamplingCache#isWater} 用 OCEAN_FLOOR_WG(海床,不含水)与
 * WORLD_SURFACE_WG(地表/水面)之差判水柱(陆路寻路已验证、距离精确、不加载区块),从根上不再误判陆地为水。
 *
 * <p>水面 y 统一取海平面(可航水域水面即海平面)。船足迹逐格要求是可航水且水深 ≥ 吃水(clearanceHeight)。
 * 玩家改动(人工运河/填海)由寻路出最终路径后的真实区块校验({@link RealWaterVerifier})兜底,不在此采样阶段处理。
 */
public final class ServerWaterRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    // 与陆路一致用 HIGH 精度(getHeight 走 AccurateHeightSampler;getOceanFloor 本就总走精确海床采样)。
    private static final PathfindingConfig.SamplingPrecision PRECISION = PathfindingConfig.SamplingPrecision.HIGH;

    private final ServerLevel level;
    private final TerrainSamplingCache terrain;
    private final Map<Long, WaterColumn> columnCache = new HashMap<>();

    public ServerWaterRouteWorld(ServerLevel level) {
        this.level = level;
        this.terrain = level == null ? null : new TerrainSamplingCache(level, PRECISION);
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        if (level == null || terrain == null) {
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
        int minDepth = Math.max(1, effective.clearanceHeight());
        // 2026-06 降采样(修长航线寻路超时):只查中心点一列,不再扫 footprint 3×3(省 9× getBaseHeight)。
        // getBaseHeight 每列烘焙 NoiseChunk 极慢,3×3 让每节点采样数爆炸,1869 格航线 90 秒跑不完。
        // 开阔水域中心点可航即整船可航;窄水道边界 1~2 格的误差由寻路出路后的 RealWaterVerifier(真实区块
        // 校验+局部绕开)兜底。boatHalfWidth 参数保留但不再在采样阶段逐格展开。
        if (!isNavigableWater(x, z, minDepth)) {
            return WaterColumn.blocked();
        }
        return WaterColumn.passable(new BlockPos(x, waterSurfaceY(x, z), z), 0.0D);
    }

    /**
     * 该列是否为可航水:是水(海床低于海平面、地表/水面高于海平面)且水深 ≥ 吃水。
     * 等价于旧 NoiseWaterSampler.isNavigableWater 的语义(seaFloor &lt;= seaLevel - minDepth),
     * 但海床来源换成距离精确、不误判陆地的 OCEAN_FLOOR_WG。
     */
    private boolean isNavigableWater(int x, int z, int minDepth) {
        if (!terrain.isWater(x, z)) {
            return false;
        }
        return terrain.getWaterDepth(x, z) >= minDepth;
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        // 世界生成高度采样不加载区块,不再受区块预算约束。
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
        // 可航水域水面即海平面。
        return level == null ? 63 : level.getSeaLevel();
    }

    private static long packXZ(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
