package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * 最终航线的<b>精确判障</b>校验+局部重寻——堵住"寻路(纯密度,判障粗)把陆地误判成可航水 → 航线穿陆 → 船卡死"。
 *
 * <p><b>2026-06 改用 NoiseChunk 精确采样</b>:寻路阶段用纯密度(便宜、跑通超远、不卡),但纯密度在海岸/河口
 * 判障粗;旧版本类用真实 {@code getFluidState} 校验,但<b>对未加载区块直接信任噪声</b>(超远航线大部分区块未
 * 加载)→ 校验形同虚设、穿陆漏过。现改用 {@link NoiseChunkHeightSampler}(自己 new NoiseChunk 整块烘焙世界
 * 生成真方块插值,<b>精确到方块、零区块加载、任意远都准</b>)逐段精确校验全程在水,穿陆段小预算 BFS 局部绕开。
 * 去掉了旧的「首尾信任区」——任何位置(含泊位附近)都精确判,不放过陆地。
 *
 * <p>采样的是世界生成原始地形(不含玩家挖的运河/填海;那部分是另一层问题,本类只保证原始陆地不穿)。
 *
 * <p><b>性能</b>:只在主线程、只在"创建航线/船自救"低频各跑一次;NoiseChunk 整块缓存(沿航线~百区块各烘焙
 * 一次,256 列共享),比寻路主循环上万节点轻;BFS 硬上限 {@link #LOCAL_BFS_MAX_CELLS} + 绕行盒剪枝。
 */
public final class RealWaterVerifier {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int SAMPLE_SPACING = 2;        // 逐段校验采样间隔(格)
    private static final int LOCAL_BFS_MAX_CELLS = 8000; // 局部绕行 BFS 探格上限(放大救中等陆颈)
    private static final int LOCAL_REROUTE_MARGIN = 48;  // 绕行盒外扩(放大;大陆靠寻路 biome 门槛绕,非这里)

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
        NoiseChunkHeightSampler sampler = NoiseChunkHeightSampler.createOrNull(level);
        if (sampler == null) {
            // 非噪声生成器(超平坦/自定义)无法 NoiseChunk 采样 → 不校验,原样返回(信任寻路)。
            return List.copyOf(waypoints);
        }
        int seaLevel = level.getSeaLevel();
        BlockPos startPt = waypoints.get(0);
        List<BlockPos> result = new ArrayList<>();
        result.add(startPt);
        int repaired = 0;
        int skipped = 0;
        for (int i = 0; i < waypoints.size() - 1; i++) {
            BlockPos a = waypoints.get(i);
            BlockPos b = waypoints.get(i + 1);
            // 去掉「首尾信任区」:NoiseChunk 精确到方块,任何位置(含泊位附近)都精确判,不放过陆地。
            if (segmentAllWater(sampler, a, b, seaLevel)) {
                result.add(b);
                continue;
            }
            // 穿陆段:局部精确 BFS 找绕行路。
            List<BlockPos> detour = localReroute(sampler, a, b, seaLevel);
            if (detour.isEmpty()) {
                // 绕不开:保留原直连段仅记录(避免一段毁掉整条远洋航线;NoiseChunk 已比纯密度精确)。
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
            LOGGER.info("[WaterPath] NoiseChunk 精确校验:绕开 {} 处穿陆段,保留 {} 处(绕不开),航点 {}→{}",
                    repaired, skipped, waypoints.size(), result.size());
        }
        return result;
    }

    /** 沿 a→b 按 SAMPLE_SPACING 采样,每点精确可航才算全程在水。 */
    private static boolean segmentAllWater(NoiseChunkHeightSampler sampler, BlockPos a, BlockPos b, int seaLevel) {
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        double distance = Math.sqrt((double) dx * dx + (double) dz * dz);
        int samples = Math.max(1, (int) Math.ceil(distance / SAMPLE_SPACING));
        for (int s = 0; s <= samples; s++) {
            double t = s / (double) samples;
            int x = (int) Math.round(a.getX() + dx * t);
            int z = (int) Math.round(a.getZ() + dz * t);
            if (!isNavigable(sampler, x, z, seaLevel)) {
                return false;
            }
        }
        return true;
    }

    /**
     * (x,z) 该列是否可航水——用 NoiseChunk 世界生成真方块高度判:海床(OCEAN_FLOOR_WG)低于海平面足够深,
     * 且地表/水面(WORLD_SURFACE_WG)不高出海平面(高出=陆地)。精确到方块、零区块加载、任意远都准。
     */
    private static boolean isNavigable(NoiseChunkHeightSampler sampler, int x, int z, int seaLevel) {
        int floor = sampler.oceanFloorWg(x, z) - 1;     // OCEAN_FLOOR_WG 返回首个空气格+1,-1 为实心海床顶
        int surface = sampler.worldSurfaceWg(x, z) - 1; // WORLD_SURFACE_WG 同理(含水面)
        // 海床在海平面下足够深(有水柱)且地表不冒出海平面(否则是陆地)→ 可航。
        return floor < seaLevel - 1 && surface <= seaLevel;
    }

    /**
     * 在 a-b 局部包围盒(外扩 LOCAL_REROUTE_MARGIN)内,沿真实可航格 1 格步长 4 邻 BFS 从 a 找到 b。
     * @return a..b 的绕行航点(含首尾);找不到/超预算返回空。
     */
    private static List<BlockPos> localReroute(NoiseChunkHeightSampler sampler, BlockPos a, BlockPos b, int seaLevel) {
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
                if (!isNavigable(sampler, nx, nz, seaLevel)) {
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
