package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 三段式贴岸航行编排(在后台线程串行跑):
 * <ol>
 *   <li>起始段:港口沿岸贴岸驶出(RealChunkRouteWorld 真实区块 + 贴岸代价)→ 大洋出口节点 A,pathA 截到 A。</li>
 *   <li>尾段:目标港口沿岸贴岸驶出 → 入口节点 B,pathB 截到 B(最终 reverse 即进港方向)。</li>
 *   <li>中段:A→B 两阶段长距离(粗大step粗寻 + 沿粗路走廊精寻,ServerWaterRouteWorld 噪声)。</li>
 * </ol>
 * 拼接 pathA + pathMid + reverse(pathB),去重接头。执行顺序「先两端求 A/B 再中段连接」。
 *
 * <p>降级:贴岸段失败 → 退泊位直连中段(锦上添花);中段失败 → 硬 NO_WATER_PATH。
 */
public final class ThreeSegmentPlanner {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int CORRIDOR_RADIUS = 48;      // 中段精阶段走廊半径
    private static final int DEDUP_DIST = 16;           // 拼接接头去重阈值(格)

    private ThreeSegmentPlanner() {
    }

    public static WaterRouteResult<List<BlockPos>> runSerial(
            RealChunkRouteWorld startWorld,
            RealChunkRouteWorld endWorld,
            ServerWaterRouteWorld midWorld,
            BlockPos srcBerth,
            BlockPos tgtBerth,
            int radius,
            int threshold) {
        try {
            WaterRoutePolicy coastal = WaterRoutePolicy.coastalHighPrecision();

            // ---- 段1:起始贴岸 → 出口 A ----
            BlockPos startGoal = CoastalExitResolver.oceanGoal(startWorld, srcBerth, radius);
            List<BlockPos> pathA = runPathfinder(startWorld, srcBerth, startGoal, coastal);
            BlockPos nodeA;
            if (pathA == null || pathA.size() < 2) {
                LOGGER.warn("[WaterPath] 三段:起始贴岸段失败,降级泊位直连中段");
                pathA = new ArrayList<>(List.of(new BlockPos(srcBerth.getX(), midWorld.waterSurfaceY(srcBerth.getX(), srcBerth.getZ()), srcBerth.getZ())));
                nodeA = pathA.get(0);
            } else {
                nodeA = CoastalExitResolver.extractExitNode(startWorld, pathA, threshold);
                pathA = new ArrayList<>(CoastalExitResolver.truncateTo(pathA, nodeA));
            }

            // ---- 段3:尾段贴岸 → 入口 B ----
            BlockPos endGoal = CoastalExitResolver.oceanGoal(endWorld, tgtBerth, radius);
            List<BlockPos> pathB = runPathfinder(endWorld, tgtBerth, endGoal, coastal);
            BlockPos nodeB;
            if (pathB == null || pathB.size() < 2) {
                LOGGER.warn("[WaterPath] 三段:尾段贴岸段失败,降级泊位直连中段");
                pathB = new ArrayList<>(List.of(new BlockPos(tgtBerth.getX(), midWorld.waterSurfaceY(tgtBerth.getX(), tgtBerth.getZ()), tgtBerth.getZ())));
                nodeB = pathB.get(0);
            } else {
                nodeB = CoastalExitResolver.extractExitNode(endWorld, pathB, threshold);
                pathB = new ArrayList<>(CoastalExitResolver.truncateTo(pathB, nodeB));
            }

            // ---- 段2:中段 A→B 两阶段长距离 ----
            List<BlockPos> pathMid = runMidTwoPhase(midWorld, nodeA, nodeB);
            if (pathMid == null || pathMid.size() < 2) {
                LOGGER.warn("[WaterPath] 三段:中段长距离失败 → NO_WATER_PATH");
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
            }

            // ---- 拼接 pathA + pathMid + reverse(pathB) ----
            List<BlockPos> full = new ArrayList<>(pathA);
            appendDedup(full, pathMid);
            List<BlockPos> revB = new ArrayList<>(pathB);
            Collections.reverse(revB);
            appendDedup(full, revB);

            LOGGER.info("[WaterPath] 三段拼接:起始{}+中段{}+尾段{} → {} 航点 (A={} B={})",
                    pathA.size(), pathMid.size(), pathB.size(), full.size(), nodeA, nodeB);
            return full.size() < 2
                    ? WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH)
                    : WaterRouteResult.success(full);
        } catch (Throwable t) {
            LOGGER.error("[WaterPath] 三段编排异常", t);
            return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
        }
    }

    /** 中段两阶段:粗大step粗寻 → 沿粗路走廊小step精寻。精寻失败则退回粗路。 */
    private static List<BlockPos> runMidTwoPhase(ServerWaterRouteWorld midWorld, BlockPos a, BlockPos b) {
        List<BlockPos> coarse = runPathfinder(midWorld, a, b, WaterRoutePolicy.longDistance());
        if (coarse == null || coarse.size() < 2) {
            return null;
        }
        CorridorWaterRouteWorld corridor = new CorridorWaterRouteWorld(midWorld, coarse, CORRIDOR_RADIUS);
        List<BlockPos> refined = runPathfinder(corridor, a, b, WaterRoutePolicy.longDistanceRefine());
        return (refined != null && refined.size() >= 2) ? refined : coarse;
    }

    /** 复用 WaterRoutePathfinder 跑一段(后台同步 do/while step 到完成或 wall-clock 超时)。 */
    private static List<BlockPos> runPathfinder(WaterRouteWorld world, BlockPos start, BlockPos goal, WaterRoutePolicy policy) {
        WaterRoutePathfinder pf = new WaterRoutePathfinder(world, start, goal, policy);
        long deadline = System.nanoTime() + (long) policy.timeoutTicks() * 50L * 1_000_000L;
        WaterRoutePathfinder.Status st;
        do {
            st = pf.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
        } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);
        return st == WaterRoutePathfinder.Status.SUCCESS ? pf.path() : null;
    }

    /** 把 src 接到 dst 尾部:若 src 首点与 dst 末点很近(<DEDUP_DIST)则跳过首点,避免接头重复/折角。 */
    private static void appendDedup(List<BlockPos> dst, List<BlockPos> src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        int from = 0;
        if (!dst.isEmpty()) {
            BlockPos tail = dst.get(dst.size() - 1);
            BlockPos head = src.get(0);
            long dx = tail.getX() - head.getX(), dz = tail.getZ() - head.getZ();
            if (dx * dx + dz * dz < (long) DEDUP_DIST * DEDUP_DIST) {
                from = 1;
            }
        }
        for (int i = from; i < src.size(); i++) {
            dst.add(src.get(i));
        }
    }
}
