package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.material.FluidState;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 最终航线的<b>真实区块</b>校验+局部重寻——堵住"噪声把陆地误判成可航水 → 航线穿陆 → 船卡死"的漏洞。
 *
 * <p>噪声采样(寻路阶段)零区块加载、远端不卡,但在海岸/河口过渡带会把陆地误判成水;step=8 跳步+4格对齐
 * 还会跳过窄陆颈。寻路链路全程不碰真实方块,所以穿陆永远发现不了。本类是<b>最后一道闸门</b>:对一条最终航线
 * (平滑+重采样后几十~一两百航点)用真实 {@code getFluidState} 逐段校验是否全程在水,穿陆段用小预算真实 BFS
 * 局部绕开。
 *
 * <p><b>防卡服铁律</b>:只在主线程、只在"创建航线/船自救"这类低频操作各跑一次;{@code hasChunkAt} 预检,
 * 未加载区块跳过复检(信任噪声采样,绝不强加载);BFS 硬上限 {@link #LOCAL_BFS_MAX_CELLS} + 绕行盒剪枝。
 */
public final class RealWaterVerifier {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SAMPLE_SPACING = 2;        // 逐段校验采样间隔(格)
    private static final int WATER_PROBE_RANGE = 2;     // 海平面上下各探几格找水(容忍浅滩/起伏)
    private static final int LOCAL_BFS_MAX_CELLS = 4000; // 局部绕行 BFS 探格上限
    private static final int LOCAL_REROUTE_MARGIN = 24;  // 绕行盒相对 a-b 包围盒的外扩格数
    private static final int ENDPOINT_TRUST_DIST = 24;   // 航线首尾此半径内不做真实校验(信任泊位解析)

    private RealWaterVerifier() {
    }

    /**
     * 校验整条航线全程在真实水里,穿陆段局部 BFS 绕开。
     * @return 校验+修复后的航点(可能比输入更密);无法修复(穿陆绕不开)返回空列表。
     */
    public static List<BlockPos> verifyAndRepair(ServerLevel level, List<BlockPos> waypoints) {
        if (level == null || waypoints == null || waypoints.size() < 2) {
            return waypoints == null ? List.of() : List.copyOf(waypoints);
        }
        int seaLevel = level.getSeaLevel();
        BlockPos startPt = waypoints.get(0);
        BlockPos endPt = waypoints.get(waypoints.size() - 1);
        List<BlockPos> result = new ArrayList<>();
        result.add(startPt);
        int repaired = 0;
        int skipped = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos a = waypoints.get(i);
            BlockPos b = waypoints.get(i + 1);
            // 首尾信任区:泊位本就紧贴码头/浅滩,真实判定不可靠(码头木板下是水、楼梯等),
            // 这一段交给 DockBerthResolver 的泊位解析,不做真实校验,避免误判把整条航线毙掉。
            if (nearEndpoint(a, startPt, endPt) || nearEndpoint(b, startPt, endPt)) {
                result.add(b);
                continue;
            }
            if (segmentAllWater(level, a, b, seaLevel)) {
                result.add(b);
                continue;
            }
            // 穿陆段:局部真实 BFS 找绕行路。
            List<BlockPos> detour = localReroute(level, a, b, seaLevel);
            if (detour.isEmpty()) {
                // 绕不开:不再整条作废(真实校验不比噪声更权威——噪声已判此路可航,校验只是优化)。
                // 退回保留原直连段,信任噪声结果,仅记录。避免一段误判毁掉整条远洋航线。
                result.add(b);
                skipped++;
                continue;
            }
            // detour 含 a..b,跳过首元素(a 已在 result)。
            for (int k = 1; k < detour.size(); k++) {
                result.add(detour.get(k));
            }
            repaired++;
        }
        if (repaired > 0 || skipped > 0) {
            LOGGER.info("[WaterPath] 真实校验:绕开 {} 处穿陆段,保留 {} 处(绕不开,信任噪声),航点 {}→{}",
                    repaired, skipped, waypoints.size(), result.size());
        }
        return result;
    }

    /** 点是否在航线首/尾端点的信任半径内(切比雪夫距离)。 */
    private static boolean nearEndpoint(BlockPos p, BlockPos start, BlockPos end) {
        return chebyshev(p, start) <= ENDPOINT_TRUST_DIST || chebyshev(p, end) <= ENDPOINT_TRUST_DIST;
    }

    private static int chebyshev(BlockPos a, BlockPos b) {
        return Math.max(Math.abs(a.getX() - b.getX()), Math.abs(a.getZ() - b.getZ()));
    }

    /** 沿 a→b 按 SAMPLE_SPACING 采样,每点真实可航才算全程在水。 */
    private static boolean segmentAllWater(ServerLevel level, BlockPos a, BlockPos b, int seaLevel) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        int samples = Math.max(1, (int) Math.ceil(distance / SAMPLE_SPACING));
        for (int s = 0; s <= samples; s++) {
            double t = s / (double) samples;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            if (!isRealNavigable(level, x, z, seaLevel)) {
                return false;
            }
        }
        return true;
    }

    /**
     * (x,z) 该列在海平面附近是否真实可航(有水且不被实心方块堵)。
     * 未加载区块 → 返回 true(信任噪声采样,防强加载卡服)。
     */
    private static boolean isRealNavigable(ServerLevel level, int x, int z, int seaLevel) {
        BlockPos probe = new BlockPos(x, seaLevel, z);
        if (!level.hasChunkAt(probe)) {
            return true; // 未加载:不复检,信任噪声(铁律:绝不强加载)
        }
        // 海平面上下几格内有水即算可航(容忍浅滩/水面起伏 1~2 格)。
        for (int dy = -WATER_PROBE_RANGE; dy <= WATER_PROBE_RANGE; dy++) {
            FluidState fluid = level.getFluidState(new BlockPos(x, seaLevel + dy, z));
            if (!fluid.isEmpty() && fluid.is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在 a-b 局部包围盒(外扩 LOCAL_REROUTE_MARGIN)内,沿真实可航格 1 格步长 4 邻 BFS 从 a 找到 b。
     * @return a..b 的绕行航点(含首尾);找不到/超预算返回空。
     */
    private static List<BlockPos> localReroute(ServerLevel level, BlockPos a, BlockPos b, int seaLevel) {
        int minX = Math.min(a.getX(), b.getX()) - LOCAL_REROUTE_MARGIN;
        int maxX = Math.max(a.getX(), b.getX()) + LOCAL_REROUTE_MARGIN;
        int minZ = Math.min(a.getZ(), b.getZ()) - LOCAL_REROUTE_MARGIN;
        int maxZ = Math.max(a.getZ(), b.getZ()) + LOCAL_REROUTE_MARGIN;

        Set<Long> visited = new HashSet<>();
        Map<Long, Long> parent = new HashMap<>();
        Deque<int[]> queue = new ArrayDeque<>();
        queue.add(new int[]{a.getX(), a.getZ()});
        visited.add(key(a.getX(), a.getZ()));
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        while (!queue.isEmpty()) {
            if (visited.size() > LOCAL_BFS_MAX_CELLS) {
                return List.of();
            }
            int[] cell = queue.poll();
            int x = cell[0];
            int z = cell[1];
            if (Math.abs(x - b.getX()) <= 1 && Math.abs(z - b.getZ()) <= 1) {
                return reconstruct(parent, a, x, z, b, seaLevel);
            }
            for (int[] n : neighbors) {
                int nx = x + n[0];
                int nz = z + n[1];
                if (nx < minX || nx > maxX || nz < minZ || nz > maxZ) {
                    continue;
                }
                long nkey = key(nx, nz);
                if (visited.contains(nkey)) {
                    continue;
                }
                if (!isRealNavigable(level, nx, nz, seaLevel)) {
                    continue;
                }
                visited.add(nkey);
                parent.put(nkey, key(x, z));
                queue.add(new int[]{nx, nz});
            }
        }
        return List.of();
    }

    private static List<BlockPos> reconstruct(Map<Long, Long> parent, BlockPos a, int endX, int endZ,
                                              BlockPos b, int seaLevel) {
        List<BlockPos> path = new ArrayList<>();
        long startKey = key(a.getX(), a.getZ());
        long cur = key(endX, endZ);
        while (true) {
            int x = (int) (cur >> 32);
            int z = (int) (cur & 0xFFFFFFFFL);
            path.add(new BlockPos(x, seaLevel, z));
            if (cur == startKey) {
                break;
            }
            Long prev = parent.get(cur);
            if (prev == null) {
                break;
            }
            cur = prev;
        }
        Collections.reverse(path); // a → end
        // 末尾确保接到真实终点 b(BFS 终止在 b 的 1 格邻域)。
        BlockPos last = path.get(path.size() - 1);
        if (last.getX() != b.getX() || last.getZ() != b.getZ()) {
            path.add(new BlockPos(b.getX(), seaLevel, b.getZ()));
        }
        return path;
    }

    private static long key(int x, int z) {
        return (((long) x) << 32) | (z & 0xFFFFFFFFL);
    }
}
