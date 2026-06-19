package com.monpai.sailboatmod.route.water;

import com.monpai.sailboatmod.road.pathfinding.cache.NoiseChunkHeightSampler;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

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

    // ---- 中段真实区块「分批加载」参数(REALCHUNK 模式;NOISE 模式不加载,走 NoiseChunk 精判)----
    private static final int SNAP_RADIUS = 48;          // 单个走廊真实快照半径(格):覆盖走廊半径 + 锚点间隙
    private static final int ANCHOR_SPACING = 64;       // 沿粗路取锚点的间距(格):相邻快照在粗路方向重叠
    private static final long SNAP_TIMEOUT_MS = 15000;  // 单批快照主线程乒乓超时 → 该批跳过(缝隙交 NoiseChunk 兜底)
    private static final long MID_DEADLINE_MS = 300000;  // 中段真实精寻总 wall-clock 上限(5 分钟,只防死循环,不防总量)

    /** 进度回调(percent 0~100,stage 文案);后台各阶段调用,实现里切主线程更新 BossBar。 */
    @FunctionalInterface
    public interface ProgressSink {
        void update(int percent, String stage);

        ProgressSink NOOP = (p, s) -> { };
    }

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
        return runSerial(startWorld, endWorld, midWorld, srcBerth, tgtBerth, radius, threshold, ProgressSink.NOOP);
    }

    public static WaterRouteResult<List<BlockPos>> runSerial(
            RealChunkRouteWorld startWorld,
            RealChunkRouteWorld endWorld,
            ServerWaterRouteWorld midWorld,
            BlockPos srcBerth,
            BlockPos tgtBerth,
            int radius,
            int threshold,
            ProgressSink progress) {
        ProgressSink prog = progress == null ? ProgressSink.NOOP : progress;
        try {
            WaterRoutePolicy segPolicy = WaterRoutePolicy.realChunkSegment();
            // 泊位信任:起终段对各自泊位邻域无条件可航(消除真实区块 vs 噪声泊位解析器判定打架 → 不再降级)。
            startWorld.setTrustedBerth(srcBerth);
            endWorld.setTrustedBerth(tgtBerth);

            // ---- 段1:起点真实区块驶向快照边缘大洋衔接点 A;nodeA = pathA 真实末点 ----
            prog.update(3, "起点港口寻路");
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
            prog.update(6, "终点港口寻路");
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

            // ---- 段2:中段 A→B(噪声粗路导向 + 走廊精寻;模式见 WaterMidMode)----
            List<BlockPos> pathMid = runMidTwoPhase(midWorld, nodeA, nodeB, prog);
            if (pathMid == null || pathMid.size() < 2) {
                LOGGER.warn("[WaterPath] 三段:中段长距离失败 → NO_WATER_PATH");
                return WaterRouteResult.failure(WaterRouteFailureReason.NO_WATER_PATH);
            }
            prog.update(92, "拼接平滑");

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

    /**
     * 中段:<b>噪声粗路只当导向走廊</b> → 沿走廊用「真相采样器」精寻得不穿陆的真实地形路。
     * 真相源由 {@link WaterMidMode} 指令切换:
     * <ul>
     *   <li>{@link WaterMidMode#NOISE}(默认):走廊内用 NoiseChunk 精判(零加载、精确到方块、修穿陆),几秒出路。</li>
     *   <li>{@link WaterMidMode#REALCHUNK}:沿走廊<b>分批加载真实区块</b>(玩家运河/填海算数),慢、带进度条。
     *       缝隙(批与批之间)由 NoiseChunk 兜底,不切断走廊。</li>
     * </ul>
     * 走廊精寻失败 → 放宽走廊半径重试一次 → 仍失败退回噪声粗路 base(有路、可能穿陆,极少触发)。
     */
    private static List<BlockPos> runMidTwoPhase(ServerWaterRouteWorld midWorld, BlockPos a, BlockPos b, ProgressSink prog) {
        // 1) 噪声粗寻:只取折线当走廊中心,不信它判可航。
        prog.update(10, "粗寻导向");
        List<BlockPos> coarse = runPathfinder(midWorld, a, b, WaterRoutePolicy.longDistance());
        if (coarse == null || coarse.size() < 2) {
            return null;
        }

        ServerLevel level = midWorld.level();
        int seaY = midWorld.waterSurfaceY(0, 0);
        NoiseChunkHeightSampler noiseFallback = level == null ? null : NoiseChunkHeightSampler.createOrNull(level);
        WaterMidMode mode = WaterMidMode.current();

        // 2) 按模式备「真相采样器」:NOISE 空快照(全走 NoiseChunk 精判);REALCHUNK 沿走廊分批加载真实快照。
        List<RealChunkRouteWorld> snapshots = new ArrayList<>();
        if (mode == WaterMidMode.REALCHUNK && level != null) {
            snapshots = loadCorridorSnapshots(level, coarse, prog);
            if (snapshots == null) {
                // 加载被打断(server 关停/全超时)→ 退回 NoiseChunk 模式继续(有路、不穿陆)。
                snapshots = new ArrayList<>();
                LOGGER.warn("[WaterPath] 中段真实快照分批加载异常,退回 NoiseChunk 精判");
            }
        }

        // 3) 走廊精寻(真实地形判定)。失败放宽走廊半径重试一次。
        prog.update(mode == WaterMidMode.REALCHUNK ? 80 : 30,
                mode == WaterMidMode.REALCHUNK ? "真实精寻" : "精寻");
        List<BlockPos> refined = refineInCorridor(snapshots, coarse, noiseFallback, seaY, a, b, CORRIDOR_RADIUS);
        if (refined == null || refined.size() < 2) {
            LOGGER.info("[WaterPath] 中段走廊精寻失败(半径{}),放宽到{}重试", CORRIDOR_RADIUS, CORRIDOR_RADIUS * 2);
            refined = refineInCorridor(snapshots, coarse, noiseFallback, seaY, a, b, CORRIDOR_RADIUS * 2);
        }
        prog.update(90, "中段完成");
        if (refined != null && refined.size() >= 2) {
            LOGGER.info("[WaterPath] 中段{}模式精寻完成:粗路{}→精路{}航点 (快照{}块)",
                    mode.name().toLowerCase(java.util.Locale.ROOT), coarse.size(), refined.size(), snapshots.size());
            return refined;
        }
        LOGGER.warn("[WaterPath] 中段走廊精寻两次均失败 → 退回噪声粗路(可能穿陆,罕见)");
        return coarse;
    }

    /** 在走廊真相采样器({@link CorridorRealChunkWorld})上跑双向 A* 精寻。 */
    private static List<BlockPos> refineInCorridor(List<RealChunkRouteWorld> snapshots, List<BlockPos> coarse,
                                                   NoiseChunkHeightSampler fallback, int seaY,
                                                   BlockPos a, BlockPos b, int corridorRadius) {
        CorridorRealChunkWorld world = new CorridorRealChunkWorld(snapshots, coarse, corridorRadius, fallback, seaY);
        return runPathfinder(world, a, b, WaterRoutePolicy.longDistanceRefine());
    }

    /**
     * REALCHUNK 模式:沿粗路折线按 {@link #ANCHOR_SPACING} 取锚点,逐锚点<b>主线程乒乓</b>加载半径 {@link #SNAP_RADIUS}
     * 的真实区块快照(每锚点一个独立 {@code server.execute},单 tick 只加载一个小快照不卡服),累积成快照列表。
     * 带进度上报。任一锚点超时跳过(缝隙交 NoiseChunk 兜底)。总耗时超 {@link #MID_DEADLINE_MS} 提前收尾。
     *
     * <p><b>不死锁</b>:主线程 fire-and-forget 加载完填 future;后台 {@code future.get(timeout)} 单向等不持锁。
     * <p><b>无总量上限</b>:慢可以(5min),靠单批小快照 + 批间让出主线程 tick 防卡服,不靠总区块数闸门。
     * @return 快照列表;server 不可用/全程异常返回 null(调用方退回 NoiseChunk)。
     */
    private static List<RealChunkRouteWorld> loadCorridorSnapshots(ServerLevel level, List<BlockPos> coarse, ProgressSink prog) {
        if (level == null || level.getServer() == null) {
            return null;
        }
        List<BlockPos> anchors = sampleAnchors(coarse, ANCHOR_SPACING);
        if (anchors.isEmpty()) {
            return new ArrayList<>();
        }
        List<RealChunkRouteWorld> out = new ArrayList<>();
        long t0 = System.nanoTime();
        int total = anchors.size();
        for (int i = 0; i < total; i++) {
            if ((System.nanoTime() - t0) / 1_000_000L > MID_DEADLINE_MS) {
                LOGGER.warn("[WaterPath] 中段真实快照加载超 {}ms,已加载 {}/{} 锚点,缝隙交 NoiseChunk 兜底",
                        MID_DEADLINE_MS, i, total);
                break;
            }
            BlockPos anchor = anchors.get(i);
            RealChunkRouteWorld snap = loadSnapshotOnMainThread(level, anchor);
            if (snap != null) {
                out.add(snap);
            }
            // 加载占进度 15~80%。
            int percent = 15 + (int) Math.round((i + 1) / (double) total * 65.0);
            prog.update(percent, "走廊加载 " + (i + 1) + "/" + total);
        }
        return out;
    }

    /** 主线程乒乓加载一个走廊真实快照(超时/异常返回 null,该锚点缝隙交 NoiseChunk)。 */
    private static RealChunkRouteWorld loadSnapshotOnMainThread(ServerLevel level, BlockPos center) {
        CompletableFuture<RealChunkRouteWorld> future = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                future.complete(RealChunkRouteWorld.load(level, center, SNAP_RADIUS));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(SNAP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 沿粗路按弧长每 spacing 格取锚点(含首尾)。 */
    private static List<BlockPos> sampleAnchors(List<BlockPos> path, int spacing) {
        List<BlockPos> out = new ArrayList<>();
        if (path == null || path.isEmpty()) {
            return out;
        }
        out.add(path.get(0));
        double acc = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            acc += Math.sqrt(a.distSqr(b));
            if (acc >= spacing) {
                out.add(b);
                acc = 0.0D;
            }
        }
        BlockPos last = path.get(path.size() - 1);
        if (!out.get(out.size() - 1).equals(last)) {
            out.add(last);
        }
        return out;
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
