package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 中段「两阶段长距离」的精阶段走廊约束:包装 {@link ServerWaterRouteWorld},只在粗路径(coarsePath)±走廊半径
 * 内放行,把精寻限制在粗路走廊里(复刻 RoadWeaver 的 quantized 沿粗路精寻,但复用现有噪声采样)。
 *
 * <p>粗阶段大 step 跑出 coarsePath → 精阶段小 step 在 coarsePath 附近 corridorRadius 走廊内再跑,得贴合的精路。
 */
public final class CorridorWaterRouteWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    private final ServerWaterRouteWorld delegate;
    private final List<BlockPos> corridor;   // 粗路径折线
    private final int corridorRadiusSq;

    public CorridorWaterRouteWorld(ServerWaterRouteWorld delegate, List<BlockPos> corridor, int corridorRadius) {
        this.delegate = delegate;
        this.corridor = corridor;
        this.corridorRadiusSq = corridorRadius * corridorRadius;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        if (!withinCorridor(x, z)) {
            return WaterColumn.blocked();
        }
        return delegate.sample(x, z, policy);
    }

    /** (x,z) 到粗路折线的最近距离是否在走廊半径内。 */
    private boolean withinCorridor(int x, int z) {
        if (corridor == null || corridor.isEmpty()) {
            return true; // 无走廊则不约束
        }
        for (int i = 0; i < corridor.size() - 1; i++) {
            if (distSqToSegment(x, z, corridor.get(i), corridor.get(i + 1)) <= corridorRadiusSq) {
                return true;
            }
        }
        // 单点或末点附近
        BlockPos last = corridor.get(corridor.size() - 1);
        long dx = x - last.getX(), dz = z - last.getZ();
        return dx * dx + dz * dz <= corridorRadiusSq;
    }

    /** 点 (px,pz) 到线段 a-b 的平方距离(2D,XZ 平面)。 */
    private static long distSqToSegment(int px, int pz, BlockPos a, BlockPos b) {
        long ax = a.getX(), az = a.getZ(), bx = b.getX(), bz = b.getZ();
        long abx = bx - ax, abz = bz - az;
        long apx = px - ax, apz = pz - az;
        long abLenSq = abx * abx + abz * abz;
        if (abLenSq == 0) {
            return apx * apx + apz * apz;
        }
        // t = clamp(dot(ap,ab)/|ab|^2, 0, 1),用浮点算投影点
        double t = (double) (apx * abx + apz * abz) / (double) abLenSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double cx = ax + t * abx, cz = az + t * abz;
        double ddx = px - cx, ddz = pz - cz;
        return Math.round(ddx * ddx + ddz * ddz);
    }

    @Override
    public boolean canLoadMoreChunks(int requested) {
        return delegate.canLoadMoreChunks(requested);
    }

    @Override
    public int consumedChunkLoads() {
        return delegate.consumedChunkLoads();
    }

    @Override
    public boolean isBerthWater(int x, int z, WaterRoutePolicy policy) {
        return sample(x, z, policy).passable();
    }

    @Override
    public int waterSurfaceY(int x, int z) {
        return delegate.waterSurfaceY(x, z);
    }
}
