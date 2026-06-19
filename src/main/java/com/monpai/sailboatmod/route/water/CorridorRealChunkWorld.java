package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 中段「真实区块走廊精寻」的采样世界:噪声粗路只当<b>导向走廊</b>,沿走廊<b>分批加载真实区块</b>拼成本采样器,
 * A* 在它上面跑得到「真实地形算数、不穿陆」的中段(玩家挖的运河/填海也算数)。
 *
 * <p><b>构成</b>:一批已加载好的 {@link RealChunkRouteWorld} 方形快照(沿粗路折线分批加载,见
 * {@link ThreeSegmentPlanner})+ 走廊折线 + 走廊半径。{@link #sample} 逻辑:
 * <ol>
 *   <li>走廊外(到粗路折线距离 &gt; 半径)→ blocked(把精寻收在走廊里,不让它满地图发散)。</li>
 *   <li>走廊内:遍历找<b>覆盖</b>该点的真实快照,查它(真实方块判可航 + 近海偏好)。</li>
 *   <li>走廊内但无快照覆盖(批与批之间的缝隙)→ 回退 {@link NoiseChunkHeightSampler} 精判(零加载、整块烘焙、
 *       精确到方块),保证缝隙不被误判 blocked 切断走廊。</li>
 * </ol>
 *
 * <p><b>线程安全</b>:快照在主线程预加载完成后,本采样器只读(纯查 final 数组 / NoiseChunk 缓存),后台 A* 安全。
 */
public final class CorridorRealChunkWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    private final List<RealChunkRouteWorld> snapshots; // 沿走廊分批加载的真实快照(主线程预加载完)
    private final List<BlockPos> corridor;             // 粗路折线(走廊中心)
    private final long corridorRadiusSq;
    private final NoiseChunkHeightSampler fallback;    // 缝隙回退(可 null)
    private final int seaLevel;

    public CorridorRealChunkWorld(List<RealChunkRouteWorld> snapshots,
                                  List<BlockPos> corridor,
                                  int corridorRadius,
                                  NoiseChunkHeightSampler fallback,
                                  int seaLevel) {
        this.snapshots = snapshots;
        this.corridor = corridor;
        this.corridorRadiusSq = (long) corridorRadius * corridorRadius;
        this.fallback = fallback;
        this.seaLevel = seaLevel;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        if (!withinCorridor(x, z)) {
            return WaterColumn.blocked();
        }
        // 找覆盖该点的真实快照(后加载的优先,通常走廊上点只被 1~2 个快照覆盖)。
        for (int i = snapshots.size() - 1; i >= 0; i--) {
            RealChunkRouteWorld snap = snapshots.get(i);
            if (snap.covers(x, z)) {
                return snap.sample(x, z, policy);
            }
        }
        // 走廊内但批缝隙无快照覆盖:NoiseChunk 精判回退(零加载、精确到方块)。
        return fallbackSample(x, z);
    }

    /** 缝隙回退:NoiseChunk 整块烘焙真方块高度判可航(同 RealWaterVerifier.isNavigable 口径)。 */
    private WaterColumn fallbackSample(int x, int z) {
        if (fallback == null) {
            return WaterColumn.blocked();
        }
        int floor = fallback.oceanFloorWg(x, z) - 1;
        int surface = fallback.worldSurfaceWg(x, z) - 1;
        boolean nav = floor < seaLevel - 1 && surface <= seaLevel;
        return nav ? WaterColumn.passable(new BlockPos(x, seaLevel, z), 0.0D) : WaterColumn.blocked();
    }

    /** (x,z) 到粗路折线最近距离是否在走廊半径内。 */
    private boolean withinCorridor(int x, int z) {
        if (corridor == null || corridor.isEmpty()) {
            return true;
        }
        for (int i = 0; i < corridor.size() - 1; i++) {
            if (distSqToSegment(x, z, corridor.get(i), corridor.get(i + 1)) <= corridorRadiusSq) {
                return true;
            }
        }
        BlockPos last = corridor.get(corridor.size() - 1);
        long dx = x - last.getX(), dz = z - last.getZ();
        return dx * dx + dz * dz <= corridorRadiusSq;
    }

    /** 点 (px,pz) 到线段 a-b 的平方距离(2D,XZ;复刻 CorridorWaterRouteWorld)。 */
    private static long distSqToSegment(int px, int pz, BlockPos a, BlockPos b) {
        long ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ();
        long abx = bx - ax, abz = bz - az;
        long apx = px - ax, apz = pz - az;
        long abLenSq = abx * abx + abz * abz;
        if (abLenSq == 0) {
            return apx * apx + apz * apz;
        }
        double t = (double) (apx * abx + apz * abz) / (double) abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * abx, cz = az + t * abz;
        double ddx = px - cx, ddz = pz - cz;
        return Math.round(ddx * ddx + ddz * ddz);
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        return false; // 快照已预加载,A* 不再加载
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
        return seaLevel;
    }
}
