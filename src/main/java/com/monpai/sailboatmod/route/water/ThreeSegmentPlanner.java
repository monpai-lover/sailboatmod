package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 两段式航行编排(在后台线程串行跑;类名沿用 ThreeSegmentPlanner 保持接线不变,实为「真实起终 + 噪声中段」两段):
 * <ol>
 *   <li>起始段:从港口泊位用真实区块(RealChunkRouteWorld + 近海偏好 + 泊位信任)驶到快照边缘大洋方向衔接点 A,
 *       pathA 末点 = nodeA。</li>
 *   <li>尾段:目标港口泊位同理驶到衔接点 B,pathB 末点 = nodeB(最终 reverse 即进港方向)。</li>
 *   <li>中段:nodeA→nodeB 两阶段长距离(粗大step粗寻 + 沿粗路走廊精寻,ServerWaterRouteWorld 噪声)。</li>
 * </ol>
 * 拼接 pathA + pathMid + reverse(pathB),去重接头。执行顺序「先两端求 A/B 再中段连接」。
 *
 * <p><b>取消贴岸高精度段</b>:旧版单独跑 coastalHighPrecision A* 求出口节点,但 berth 由噪声世界解析、贴岸段用
 * 真实区块判定,两套采样器对同一泊位打架 → 起点判不可航必降级 → 中段从 near-岸点直线穿陆。现起终段直接用真实
 * 区块 + 泊位信任(泊位无条件可航)+ 近海偏好驶到大洋衔接点,不再有「贴岸段失败→降级」退化路径。
 *
 * <p>降级:起终段失败(罕见,泊位已信任) → 退泊位直连中段;中段失败 → 硬 NO_WATER_PATH。
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
            WaterRoutePolicy segPolicy = WaterRoutePolicy.realChunkSegment();
            // 泊位信任:起终段对各自泊位邻域无条件可航(消除真实区块 vs 噪声泊位解析器判定打架 → 不再降级)。
            startWorld.setTrustedBerth(srcBerth);
            endWorld.setTrustedBerth(tgtBerth);

            // ---- 段1:起点真实区块驶向快照边缘大洋衔接点 A;nodeA = pathA 真实末点 ----
            BlockPos startGoal = CoastalExitResolver.oceanGoal(startWorld, srcBerth, radius);
            List<BlockPos> pathA = runPathfinder(startWorld, srcBerth, startGoal, segPolicy);
            BlockPos nodeA;
            if (pathA == null || pathA.size() < 2) {
                LOGGER.warn("[WaterPath] 两段:起点真实区块段失败,降级泊位直连中段 berth={}", srcBerth);
                pathA = new ArrayList<>(List.of(new BlockPos(srcBerth.getX(), midWorld.waterSurfaceY(srcBerth.getX(), srcBerth.getZ()), srcBerth.getZ())));
                nodeA = pathA.get(0);
            } else {
                pathA = new ArrayList<>(pathA);
                nodeA = pathA.get(pathA.size() - 1);
            }

            // ---- 段3:终点真实区块驶向衔接点 B;nodeB = pathB 真实末点 ----
            BlockPos endGoal = CoastalExitResolver.oceanGoal(endWorld, tgtBerth, radius);
            List<BlockPos> pathB = runPathfinder(endWorld, tgtBerth, endGoal, segPolicy);
            BlockPos nodeB;
            if (pathB == null || pathB.size() < 2) {
                LOGGER.warn("[WaterPath] 两段:终点真实区块段失败,降级泊位直连中段 berth={}", tgtBerth);
                pathB = new ArrayList<>(List.of(new BlockPos(tgtBerth.getX(), midWorld.waterSurfaceY(tgtBerth.getX(), tgtBerth.getZ()), tgtBerth.getZ())));
                nodeB = pathB.get(0);
            } else {
                pathB = new ArrayList<>(pathB);
                nodeB = pathB.get(pathB.size() - 1);
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

            // 去回头折点:三段拼接接头处(起终段 step=4 末点 vs 中段 snap 到 step=12 网格首点错位)易形成
            // 「往回折」的 zigzag,样条平滑也只能把它磨成圆滑的 zigzag。拼接后整条扫一遍,删回折/微绕中间点。
            int before = full.size();
            full = dropBackfolds(full);
            LOGGER.info("[WaterPath] 两段拼接:起始{}+中段{}+尾段{} → 去折{}→{} 航点 (A={} B={})",
                    pathA.size(), pathMid.size(), pathB.size(), before, full.size(), nodeA, nodeB);
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

    private static final double BACKFOLD_PERP_DIST = 6.0D; // 去折:中间点到 a→c 直线垂距 < 此值视作微绕/回折,删

    /**
     * 去回头折点:连续三点 a-b-c,若 b 到直线 a→c 的垂距很小(b 几乎在 a-c 连线上 = 微绕或回折赘点),删 b。
     * 接头 zigzag 的中间折点正是这种「跨出去又拐回来」的赘点,删掉即拉直。迭代到稳定(一遍删完可能露出新赘点)。
     */
    private static List<BlockPos> dropBackfolds(List<BlockPos> path) {
        if (path == null || path.size() <= 2) {
            return path;
        }
        List<BlockPos> cur = new ArrayList<>(path);
        boolean changed = true;
        while (changed && cur.size() > 2) {
            changed = false;
            List<BlockPos> out = new ArrayList<>();
            out.add(cur.get(0));
            int i = 1;
            while (i < cur.size() - 1) {
                BlockPos a = out.get(out.size() - 1);
                BlockPos b = cur.get(i);
                BlockPos c = cur.get(i + 1);
                if (perpDistToLine(b, a, c) < BACKFOLD_PERP_DIST) {
                    // b 是赘点(微绕/回折),跳过它(不加入 out),直接看 a→c。
                    changed = true;
                    i++;
                } else {
                    out.add(b);
                    i++;
                }
            }
            out.add(cur.get(cur.size() - 1)); // 末点必留
            cur = out;
        }
        return cur;
    }

    /** 点 p 到直线 a→c 的垂距(2D,XZ)。a==c 时退化为 p 到 a 的距离。 */
    private static double perpDistToLine(BlockPos p, BlockPos a, BlockPos c) {
        double acx = c.getX() - a.getX(), acz = c.getZ() - a.getZ();
        double lenSq = acx * acx + acz * acz;
        if (lenSq < 1.0E-9D) {
            double dx = p.getX() - a.getX(), dz = p.getZ() - a.getZ();
            return Math.sqrt(dx * dx + dz * dz);
        }
        double cross = Math.abs((p.getX() - a.getX()) * acz - (p.getZ() - a.getZ()) * acx);
        return cross / Math.sqrt(lenSq);
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
