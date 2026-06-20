package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * {@link WaterRouteWorld} 适配器,把 {@link RealBlockWaterMap}(真实方块水图)包成 A* 能跑的采样世界。两种用法:
 * <ul>
 *   <li><b>走廊模式(旧·三段中段)</b>:约束折线 + 半径把搜索收在带/走廊里(外 blocked),配预加载。</li>
 *   <li><b>单段模式(新·NBT 单段 A*)</b>:无约束全开 + {@link RealBlockWaterMap#enableOnDemand() 按需加载} +
 *       <b>3×3 船宽硬校验</b>({@code boatHalfWidth}) + 起终泊位信任。A* 一边搜一边 NBT 加载,前沿过的区块滑动卸载。
 *       见 {@link #singleSegment}。</li>
 * </ul>
 *
 * <p><b>缓存未命中</b>:走廊模式约束内未预加载 → 保守 {@code blocked()};单段模式触发按需加载,读不到当陆 blocked。
 *
 * <p>纯读 {@link RealBlockWaterMap}(后台线程安全)。
 */
public final class RealBlockWaterWorld implements WaterRouteWorld, DockBerthResolver.BerthWorld {
    // 泊位信任半径:此格及邻域无条件可航(中心可航即可,不做 3×3)。设 10 > step(8) 的 snap 偏移上限,
    // 保证 seed 阶段 snap 后的起/终点格(最多偏 step-1=7 格)仍落在信任区,不被近岸地形判不可航直接 0 节点失败。
    private static final int BERTH_TRUST_RADIUS = 10;

    private final RealBlockWaterMap map;
    private final List<BlockPos> constraint; // 约束折线(带中心线 / 粗路走廊);单段模式为 null=全开
    private final long radiusSq;
    private final int seaLevel;
    private final int boatHalfWidth;          // >0 时启用 3×3 船宽校验(单段模式 1=船 3×3);0=只判中线一格
    private BlockPos trustedBerthA;           // 信任泊位(起点);可 null
    private BlockPos trustedBerthB;           // 信任泊位(终点);可 null

    public RealBlockWaterWorld(RealBlockWaterMap map, List<BlockPos> constraint, int radius, int seaLevel) {
        this(map, constraint, radius, seaLevel, 0);
    }

    private RealBlockWaterWorld(RealBlockWaterMap map, List<BlockPos> constraint, int radius, int seaLevel, int boatHalfWidth) {
        this.map = map;
        this.constraint = constraint;
        this.radiusSq = (long) radius * radius;
        this.seaLevel = seaLevel;
        this.boatHalfWidth = Math.max(0, boatHalfWidth);
    }

    /**
     * 单段 NBT A* 世界:无约束全开,启用按需加载 + 滑动卸载 + 3×3 船宽校验,起终泊位信任。
     * map 必须已 {@link RealBlockWaterMap#enableOnDemand()}(调用方负责),seaY = 海平面。
     */
    public static RealBlockWaterWorld singleSegment(RealBlockWaterMap map, int seaY, int boatHalfWidth,
                                                    BlockPos berthA, BlockPos berthB) {
        RealBlockWaterWorld w = new RealBlockWaterWorld(map, null, 0, seaY, boatHalfWidth);
        w.trustedBerthA = berthA;
        w.trustedBerthB = berthB;
        return w;
    }

    /**
     * <b>两阶段·走廊精寻世界</b>(阶段二):用阶段一粗寻出的导向折线 {@code coarse} 作约束走廊(中心线 ±radius
     * 外 blocked),把精寻 A* 的搜索空间收死在粗路附近 → 双向 A* 前沿不发散(解单段无约束全开发散搜不到)。
     * 走廊内启用 3×3 船宽校验 + 起终泊位信任。map 须已 enableOnDemand。
     */
    public static RealBlockWaterWorld corridor(RealBlockWaterMap map, List<BlockPos> coarse, int radius, int seaY,
                                               int boatHalfWidth, BlockPos berthA, BlockPos berthB) {
        RealBlockWaterWorld w = new RealBlockWaterWorld(map, coarse, radius, seaY, boatHalfWidth);
        w.trustedBerthA = berthA;
        w.trustedBerthB = berthB;
        return w;
    }

    /**
     * <b>两阶段·粗走廊世界</b>(阶段一):无约束全开,大 step 粗网格省节点,求大致走向。
     * 2026-06:粗阶段也启用 3×3 船宽(boatHalfWidth=1)——避免粗路把船宽不够的窄水道当通路、精寻再被卡死/绕远。
     * 起终泊位信任(泊位贴岸时 3×3 会误判,信任区放行)。map 须已 enableOnDemand。
     */
    public static RealBlockWaterWorld coarse(RealBlockWaterMap map, int seaY, BlockPos berthA, BlockPos berthB) {
        RealBlockWaterWorld w = new RealBlockWaterWorld(map, null, 0, seaY, 1);
        w.trustedBerthA = berthA;
        w.trustedBerthB = berthB;
        return w;
    }

    @Override
    public WaterColumn sample(int x, int z, WaterRoutePolicy policy) {
        // 泊位信任:起终点邻域无条件可航(泊位可能贴岸/在窄水道,3×3 船宽会误判 blocked → 起点不可航直接失败)。
        if (isTrustedBerth(x, z)) {
            return WaterColumn.passable(new BlockPos(x, seaLevel, z), 0.0D);
        }
        if (!withinConstraint(x, z)) {
            return WaterColumn.blocked();
        }
        WaterColumn c = boatHalfWidth > 0 ? map.sampleHull(x, z, boatHalfWidth) : map.sample(x, z);
        if (c == null) {
            return WaterColumn.blocked(); // 未加载/读不到 → 保守 blocked,A* 绕开
        }
        return c;
    }

    @Override
    public String sampleDiagnostic(int x, int z, WaterRoutePolicy policy) {
        if (isTrustedBerth(x, z)) {
            return "泊位信任区(无条件可航;距A=" + dist(x, z, trustedBerthA) + " 距B=" + dist(x, z, trustedBerthB) + ")";
        }
        if (!withinConstraint(x, z)) {
            return "约束外(走廊半径外,blocked)";
        }
        if (boatHalfWidth > 0) {
            return map.sampleHullDiagnostic(x, z, boatHalfWidth);
        }
        WaterColumn c = map.sample(x, z);
        if (c == null) {
            return "区块读不到(blocked)";
        }
        return c.passable() ? "可航(中线单格)" : "非水(中线单格,blocked)";
    }

    private static int dist(int x, int z, BlockPos berth) {
        if (berth == null) {
            return -1;
        }
        return Math.max(Math.abs(x - berth.getX()), Math.abs(z - berth.getZ()));
    }

    private boolean isTrustedBerth(int x, int z) {
        return inTrust(x, z, trustedBerthA) || inTrust(x, z, trustedBerthB);
    }

    private static boolean inTrust(int x, int z, BlockPos berth) {
        return berth != null
                && Math.abs(x - berth.getX()) <= BERTH_TRUST_RADIUS
                && Math.abs(z - berth.getZ()) <= BERTH_TRUST_RADIUS;
    }

    private boolean withinConstraint(int x, int z) {
        if (constraint == null || constraint.isEmpty()) {
            return true; // 单段全开
        }
        for (int i = 0; i < constraint.size() - 1; i++) {
            if (distSqToSegment(x, z, constraint.get(i), constraint.get(i + 1)) <= radiusSq) {
                return true;
            }
        }
        BlockPos last = constraint.get(constraint.size() - 1);
        long dx = x - last.getX(), dz = z - last.getZ();
        return dx * dx + dz * dz <= radiusSq;
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
        return false;
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
