package com.monpai.sailboatmod.route.water;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 泊位连通性溯源:从一个泊位点出发,沿「船能浮的水面格」做 4 邻 BFS flood-fill,
 * 判定这片水是否通向开阔水域(而非封闭小水坑/护城河)。
 *
 * <p>设计要点:
 * <ul>
 *   <li>逐格(步长 1)4 邻扩散——连通性必须真实,不能像寻路那样步长 8 跳过窄缝误判连通。</li>
 *   <li>每格仍用 {@link WaterRouteWorld#sample} 判定(含 3×3 足迹 + 净空),保证连通的是「船真能走的水」。</li>
 *   <li>提前成功:连通水格数 ≥ {@code minOpenWaterCells} 或 离起点切比雪夫距离 ≥ {@code minOpenWaterDist}
 *       任一满足即判通海,立刻停止(省开销)。</li>
 *   <li>硬预算 {@code floodFillBudget}:最多探这么多格就停;若仍未达开阔阈值 → 判为封闭水域(false)。</li>
 * </ul>
 *
 * <p>仅在「列入候选码头列表」这一低频手动操作时对候选码头各跑一次,不在每 tick 路径上。
 */
public final class WaterConnectivityProbe {
    // 阈值为估值,实机可标定。封闭水池通常几十格;真海域/大河几百~几千格起。
    private static final int OPEN_WATER_MIN_CELLS = 400;   // 连通水面格 ≥ 此值即算通海
    private static final int OPEN_WATER_MIN_DIST = 48;     // 能从起点连通扩散到 ≥ 此格距离即算通海(zone 半径仅 12)
    private static final int FLOOD_FILL_BUDGET = 4000;     // 最多探格数上限,防超大海域无意义全扫
    private static final int CHUNK_LOAD_BUDGET = 256;      // flood-fill 自身的区块加载上限,防极端卡顿

    private WaterConnectivityProbe() {
    }

    public static boolean reachesOpenWater(WaterRouteWorld world, BlockPos start, WaterRoutePolicy policy) {
        if (world == null || start == null) {
            return false;
        }
        WaterRoutePolicy effective = policy == null ? WaterRoutePolicy.defaults() : policy;
        int chunkLoadsAtStart = world.consumedChunkLoads();

        Set<Long> visited = new HashSet<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{start.getX(), start.getZ()});
        visited.add(key(start.getX(), start.getZ()));

        int reachedCount = 0;
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            if (visited.size() >= FLOOD_FILL_BUDGET) {
                return false;
            }
            if ((world.consumedChunkLoads() - chunkLoadsAtStart) > CHUNK_LOAD_BUDGET) {
                return false;
            }
            int[] cell = queue.poll();
            int x = cell[0];
            int z = cell[1];

            WaterColumn column = world.sample(x, z, effective);
            if (column == null || !column.passable()) {
                continue;
            }
            reachedCount++;

            int dist = Math.max(Math.abs(x - start.getX()), Math.abs(z - start.getZ()));
            if (reachedCount >= OPEN_WATER_MIN_CELLS || dist >= OPEN_WATER_MIN_DIST) {
                return true;
            }

            for (int[] n : neighbors) {
                int nx = x + n[0];
                int nz = z + n[1];
                if (visited.add(key(nx, nz))) {
                    queue.add(new int[]{nx, nz});
                }
            }
        }
        return false;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
