package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * <b>运行时轻量网格 A*</b>(2026-06):autopilot 帆船连续卡住时,在船<b>已强加载</b>的周围区块里同步跑一条短程 A* 绕开
 * 卡点,治本替代「傻切下一航点」。<b>主线程同步</b>可行的前提:autopilot 用 ENTITY_TICKING 票据强加载船周围 5×5 区块
 * (AUTOPILOT_CHUNK_RADIUS=2),{@link RealBlockWaterMap#hullClear} 命中内存缓存不阻塞;再用包围盒 + 探格上限兜底,
 * 单次几毫秒内返回,不卡服。
 *
 * <p>判通行口径与生成期<b>完全一致</b>:{@link RealBlockWaterMap#hullClear}(2×3 船宽 2×2 占地全水)。这样运行时绕行
 * 结果和生成期航线同一标准,不会绕进生成期判不可航的格。8 邻网格,正交代价 1、对角 √2,曼哈顿/欧氏启发。
 *
 * <p>失败(超包围盒/超探格/无路)返回空列表,调用方退回原有异步重寻或呼救。实体无关,马车将来可复用。
 */
public final class RuntimeWaterAStar {

    private RuntimeWaterAStar() {
    }

    /** 默认包围盒外扩(格):搜索限制在 start/goal AABB 各方向外扩这么多。船强加载半径 5×5 区块=80 格,留足。 */
    public static final int DEFAULT_MARGIN = 64;
    /** 默认探格上限:超过即放弃(防主线程卡顿)。窄道短程绕行通常几百格内解决。 */
    public static final int DEFAULT_MAX_EXPANSIONS = 8000;
    /** 网格步长(格):1=逐格最精,短程绕行够快。 */
    private static final int STEP = 1;

    private static final int[] DX = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DZ = {0, 0, 1, -1, 1, -1, 1, -1};
    private static final double SQRT2 = 1.4142135623730951D;

    /**
     * 从 start 到 goal 跑 A*(2×2 hullClear 判通行,限包围盒+探格上限)。
     *
     * @param map        已 enableOnDemand 的真实方块水图(船强加载范围内 hullClear 命中缓存)
     * @param start      起点(应已是可航格;调用方用 nearestHullWater 校正)
     * @param goal       终点
     * @param halfWidth  船宽(语义已统一 2×2)
     * @param margin     包围盒外扩(传 &lt;=0 用 {@link #DEFAULT_MARGIN})
     * @param maxExpansions 探格上限(传 &lt;=0 用 {@link #DEFAULT_MAX_EXPANSIONS})
     * @return 含 start、goal 的航点折线;失败(无路/超限)返回空列表。
     */
    public static List<BlockPos> findPath(RealBlockWaterMap map, BlockPos start, BlockPos goal, int halfWidth,
                                          int margin, int maxExpansions) {
        if (map == null || start == null || goal == null) {
            return List.of();
        }
        int m = margin > 0 ? margin : DEFAULT_MARGIN;
        int cap = maxExpansions > 0 ? maxExpansions : DEFAULT_MAX_EXPANSIONS;
        int y = start.getY();

        int minX = Math.min(start.getX(), goal.getX()) - m;
        int maxX = Math.max(start.getX(), goal.getX()) + m;
        int minZ = Math.min(start.getZ(), goal.getZ()) - m;
        int maxZ = Math.max(start.getZ(), goal.getZ()) + m;

        if (!map.hullClear(start.getX(), start.getZ(), halfWidth)
                || !map.hullClear(goal.getX(), goal.getZ(), halfWidth)) {
            return List.of(); // 端点不可航 → 交调用方先校正
        }

        long startKey = key(start.getX(), start.getZ());
        long goalKey = key(goal.getX(), goal.getZ());

        Map<Long, Long> cameFrom = new HashMap<>();
        Map<Long, Double> gScore = new HashMap<>();
        PriorityQueue<Node> open = new PriorityQueue<>();
        gScore.put(startKey, 0.0D);
        open.add(new Node(start.getX(), start.getZ(), 0.0D, heuristic(start.getX(), start.getZ(), goal)));

        int expansions = 0;
        while (!open.isEmpty()) {
            if (expansions++ > cap) {
                return List.of(); // 超探格上限,放弃(主线程保护)
            }
            Node cur = open.poll();
            long curKey = key(cur.x, cur.z);
            if (curKey == goalKey) {
                return reconstruct(cameFrom, curKey, y);
            }
            double curG = gScore.getOrDefault(curKey, Double.MAX_VALUE);
            if (cur.g > curG) {
                continue; // 过期节点(已有更优)
            }
            for (int i = 0; i < DX.length; i++) {
                int nx = cur.x + DX[i] * STEP;
                int nz = cur.z + DZ[i] * STEP;
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                if (!map.hullClear(nx, nz, halfWidth)) {
                    continue;
                }
                boolean diagonal = DX[i] != 0 && DZ[i] != 0;
                if (diagonal) {
                    // 防穿角:对角移动要求两侧正交格也可航(否则贴着内角切过陆)。
                    if (!map.hullClear(cur.x + DX[i] * STEP, cur.z, halfWidth)
                            || !map.hullClear(cur.x, cur.z + DZ[i] * STEP, halfWidth)) {
                        continue;
                    }
                }
                double tentative = curG + (diagonal ? STEP * SQRT2 : STEP);
                long nKey = key(nx, nz);
                if (tentative < gScore.getOrDefault(nKey, Double.MAX_VALUE)) {
                    cameFrom.put(nKey, curKey);
                    gScore.put(nKey, tentative);
                    open.add(new Node(nx, nz, tentative, tentative + heuristic(nx, nz, goal)));
                }
            }
        }
        return List.of(); // open 耗尽无路
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> cameFrom, long goalKey, int y) {
        List<BlockPos> path = new ArrayList<>();
        Long k = goalKey;
        while (k != null) {
            path.add(new BlockPos(unpackX(k), y, unpackZ(k)));
            k = cameFrom.get(k);
        }
        Collections.reverse(path);
        return path;
    }

    private static double heuristic(int x, int z, BlockPos goal) {
        int dx = Math.abs(x - goal.getX());
        int dz = Math.abs(z - goal.getZ());
        // 八方向距离(对角优先)启发,可纳/一致。
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return min * SQRT2 + (max - min);
    }

    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int unpackX(long k) {
        return (int) (k >> 32);
    }

    private static int unpackZ(long k) {
        return (int) (k & 0xFFFFFFFFL);
    }

    private static final class Node implements Comparable<Node> {
        final int x;
        final int z;
        final double g;
        final double f;

        Node(int x, int z, double g, double f) {
            this.x = x;
            this.z = z;
            this.g = g;
            this.f = f;
        }

        @Override
        public int compareTo(Node o) {
            return Double.compare(this.f, o.f);
        }
    }
}
