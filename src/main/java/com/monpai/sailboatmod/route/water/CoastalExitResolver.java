package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.List;

/**
 * 三段式贴岸航行的「出口节点」判定:贴岸段不是寻到固定点,而是从港口沿海岸贴岸驶出,直到<b>进入大洋</b>
 * (离岸距离 ≥ 阈值)就结束,那个点就是大洋衔接节点 A/B。
 *
 * <ul>
 *   <li>{@link #oceanGoal}:给贴岸段 A* 一个「大洋方向」牵引终点(港口最开阔方向外推),A* 带贴岸代价沿岸走到那。</li>
 *   <li>{@link #extractExitNode}:从贴岸段算出的路径里提取第一个真正进大洋(off≥阈值)的点 = 出口 A/B。</li>
 * </ul>
 */
public final class CoastalExitResolver {
    private CoastalExitResolver() {
    }

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /** 港口最开阔方向:8 方向各探 ±probe 格,取离岸距离最大者(朝大洋的方向)。 */
    public static int[] openWaterDirection(RealChunkRouteWorld world, BlockPos berth, int probe) {
        int best = -1, bx = 1, bz = 0;
        for (int[] d : DIRS) {
            int off = world.offshoreDistAt(berth.getX() + d[0] * probe, berth.getZ() + d[1] * probe);
            if (off > best) {
                best = off;
                bx = d[0];
                bz = d[1];
            }
        }
        return new int[]{bx, bz};
    }

    /**
     * 大洋方向外推 goal:港口最开阔方向外推 (radius-margin) 格,落快照边缘的开阔水。贴岸段 A* 的牵引终点。
     * 不可航则螺旋找最近可航格。
     */
    public static BlockPos oceanGoal(RealChunkRouteWorld world, BlockPos berth, int radius) {
        int[] dir = openWaterDirection(world, berth, 16);
        int gx = berth.getX() + dir[0] * (radius - 8);
        int gz = berth.getZ() + dir[1] * (radius - 8);
        BlockPos goal = nearestNavigable(world, gx, gz, berth.getY());
        // 诊断:最开阔方向 + 外推目标点离岸距离(看 goal 是不是被引向贴岸/陆地方向)。
        org.slf4j.LoggerFactory.getLogger("WaterPath").info(
                "[WaterPath] 诊断 oceanGoal:berth={} 最开阔方向=({},{}) 外推点=({},{}) →goal={} goal离岸={}格 有开阔海biome={}",
                berth, dir[0], dir[1], gx, gz, goal, world.offshoreDistAt(goal.getX(), goal.getZ()), world.hasOpenOcean());
        return goal;
    }

    /** 从 (cx,cz) 螺旋向外找最近可航格(快照内)。 */
    public static BlockPos nearestNavigable(RealChunkRouteWorld world, int cx, int cz, int y) {
        if (world.isBerthWater(cx, cz, null)) {
            return new BlockPos(cx, world.seaLevel(), cz);
        }
        for (int r = 1; r <= 32; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                        continue;
                    }
                    int x = cx + dx, z = cz + dz;
                    if (world.isBerthWater(x, z, null)) {
                        return new BlockPos(x, world.seaLevel(), z);
                    }
                }
            }
        }
        return new BlockPos(cx, world.seaLevel(), cz); // 兜底
    }

    /**
     * 从贴岸段路径提取出口节点(尽量进入开阔水域):
     * 1. 快照内有海洋/深海 biome → 优先返回路径上第一个落在开阔水域(海洋/深海 biome)的点;
     * 2. 没有开阔水域 biome(整片近岸/内海)→ 退回第一个离岸距离 ≥ threshold 的点;
     * 3. 都没有 → 路径末点。
     * 这样船尽量驶到真正的开阔水域才转中段,衔接点不卡在近岸浅水/群岛缝。
     */
    public static BlockPos extractExitNode(RealChunkRouteWorld world, List<BlockPos> coastalPath, int threshold) {
        if (coastalPath == null || coastalPath.isEmpty()) {
            return null;
        }
        if (world.hasOpenOcean()) {
            for (BlockPos p : coastalPath) {
                if (world.isOpenOceanAt(p.getX(), p.getZ())) {
                    return p; // 优先:真正的开阔水域(海洋/深海 biome)
                }
            }
            // 快照有开阔水域但这条贴岸路没走到 → 退回离岸阈值
        }
        for (BlockPos p : coastalPath) {
            if (world.offshoreDistAt(p.getX(), p.getZ()) >= threshold) {
                return p;
            }
        }
        return coastalPath.get(coastalPath.size() - 1);
    }

    /** 把路径截到 exit 节点为止([0..exit])。exit 不在路径里则原样返回。 */
    public static List<BlockPos> truncateTo(List<BlockPos> path, BlockPos exit) {
        if (path == null || exit == null) {
            return path;
        }
        int idx = path.indexOf(exit);
        if (idx < 0) {
            return path;
        }
        return List.copyOf(path.subList(0, idx + 1));
    }
}
