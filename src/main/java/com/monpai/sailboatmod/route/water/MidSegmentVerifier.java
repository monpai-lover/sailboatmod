package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 中段「真实区块分批验证 + 穿陆接力绕行」纠错层(在后台 WaterRoute-Worker 线程跑)。
 *
 * <p><b>为什么</b>:中段噪声粗路(ServerWaterRouteWorld)看世界生成原始地形,看不到玩家挖的运河/填海,纯密度
 * 也漏窄陆/小岛 → 中段穿陆(船卡死)。本层沿粗路抽样,<b>分批小量加载真实区块</b>验证每个节点在水/陆,穿陆处
 * 退回上一个好节点用<b>真实快照高精度寻路接力绕行</b>到下一个好节点,最终得到玩家运河算数、不穿陆的高质量航线。
 *
 * <p><b>线程模型(不死锁)</b>:真实区块加载必须主线程。后台算出要验证/绕行的范围 → {@code server.execute} 把
 * 「加载+读快照」<b>单向投递</b>主线程(fire-and-forget,主线程下个 tick 跑完 {@code future.complete}) → 后台
 * {@code future.get(timeout)} <b>单向等</b>且此刻不持主线程要拿的锁 → 环断、不死锁。主线程永不回头等后台。
 * 超时/server 关停 → future 超时返回 → DEGRADE(退回粗路),绝不挂死(worker 是 daemon)。
 *
 * <p><b>预算三层闸</b>(任一超即 DEGRADE 退回粗路,保证有路、不卡服):单批节点数 + 单跳绕行加载半径/节点预算
 * + 全局真实加载区块总数。<b>任何降级都返回非空中段</b>(退回 coarse),绝不返回空让 task 永久 pending。
 */
public final class MidSegmentVerifier {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int K = 256;                    // 沿粗路抽样间隔(格)
    private static final int BATCH_NODES = 4;             // 单批合并验证的节点数(区块并集一次 force/读/释放)
    private static final int VERIFY_RADIUS = 16;          // 验证单节点真实区块半径(格,只查中心点可航,3x3区块够、单tick轻)
    private static final long BATCH_TIMEOUT_MS = 5000;    // 单批主线程乒乓超时 → DEGRADE
    private static final int PER_DETOUR_LOAD_RADIUS = 64; // 单跳绕行局部真实快照半径(格)
    private static final int MAX_RELAY_HOPS = 6;          // 单段绕行最大接力跳数(6×64≈768 格陆宽可绕)
    private static final int TOTAL_CHUNK_BUDGET = 256;    // 整条航线真实加载区块硬上限(粗略,按 load 次数估)
    private static final long TOTAL_VERIFY_DEADLINE_MS = 30000; // 验证层 wall-clock 上限

    private MidSegmentVerifier() {
    }

    /**
     * 验证+纠错中段粗路。<b>后台线程调用。</b>
     * @param coarse runMidTwoPhase 的粗路 A→B(噪声)。
     * @return 纠错后中段(真实区块算数、不穿陆);任何降级返回 null(调用方退回 coarse)。
     */
    public static List<BlockPos> verifyAndRelay(ServerLevel level, ServerWaterRouteWorld midWorld, List<BlockPos> coarse) {
        if (level == null || level.getServer() == null || coarse == null || coarse.size() < 2) {
            return null;
        }
        long t0 = System.nanoTime();
        Budget budget = new Budget();
        List<BlockPos> nodes = sampleEveryK(coarse, K);
        if (nodes.size() < 2) {
            return null;
        }
        int seaY = midWorld.waterSurfaceY(0, 0);
        List<BlockPos> result = new ArrayList<>();
        BlockPos nGood = nodes.get(0);
        result.add(new BlockPos(nGood.getX(), seaY, nGood.getZ()));
        int detours = 0;

        int i = 1;
        while (i < nodes.size()) {
            if (budget.deadlineExceeded(t0) || budget.chunkExceeded()) {
                LOGGER.warn("[WaterPath] 中段验证 DEGRADE:总预算超(加载{}区块/{}ms)→退回粗路",
                        budget.chunksLoaded, (System.nanoTime() - t0) / 1_000_000L);
                return null;
            }
            // ---- 验证一批节点(主线程乒乓加载真实区块)----
            int batchEnd = Math.min(i + BATCH_NODES, nodes.size());
            List<BlockPos> batch = nodes.subList(i, batchEnd);
            boolean[] inWater = verifyBatchOnMainThread(level, batch, budget);
            if (inWater == null) {
                LOGGER.warn("[WaterPath] 中段验证 DEGRADE:批验证乒乓超时/异常 → 退回粗路");
                return null;
            }
            // ---- 逐节点处理 ----
            boolean detoured = false;
            for (int b = 0; b < batch.size(); b++) {
                BlockPos node = batch.get(b);
                int gi = i + b; // node 在 nodes 里的全局索引
                if (inWater[b]) {
                    // 好节点:粗路对应弧吃进 result(粗路本就连续,直接接 node)。
                    result.add(new BlockPos(node.getX(), seaY, node.getZ()));
                    nGood = node;
                } else {
                    // 穿陆:退回 nGood,绕行到下一个验证在水的节点 nNext(或 nodeB)。
                    BlockPos nNext = findNextGoodNode(level, nodes, gi, budget);
                    if (nNext == null) {
                        LOGGER.warn("[WaterPath] 中段验证 DEGRADE:找不到下一个好节点 → 退回粗路");
                        return null;
                    }
                    List<BlockPos> detour = relayDetour(level, nGood, nNext, seaY, budget);
                    if (detour == null) {
                        LOGGER.warn("[WaterPath] 中段验证 DEGRADE:接力绕行绕不过 nGood={} nNext={} → 退回粗路", nGood, nNext);
                        return null;
                    }
                    appendDedup(result, detour);
                    nGood = nNext;
                    detours++;
                    // 跳到 nNext 之后继续验证。
                    i = indexOf(nodes, nNext) + 1;
                    detoured = true;
                    break;
                }
            }
            if (!detoured) {
                i = batchEnd;
            }
        }
        // 收尾:粗路末点(nodeB)。
        BlockPos last = nodes.get(nodes.size() - 1);
        BlockPos lastSea = new BlockPos(last.getX(), seaY, last.getZ());
        if (result.isEmpty() || !result.get(result.size() - 1).equals(lastSea)) {
            result.add(lastSea);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        LOGGER.info("[WaterPath] 中段真实验证完成:抽样{}节点 绕行{}段 加载{}区块 耗时{}ms → {}航点",
                nodes.size(), detours, budget.chunksLoaded, ms, result.size());
        return result.size() >= 2 ? result : null;
    }

    /** 沿粗路按弧长每 step 格抽样(含首尾)。 */
    private static List<BlockPos> sampleEveryK(List<BlockPos> path, int step) {
        List<BlockPos> out = new ArrayList<>();
        if (path.isEmpty()) {
            return out;
        }
        out.add(path.get(0));
        double acc = 0.0D;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            acc += Math.sqrt(a.distSqr(b));
            if (acc >= step) {
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

    /**
     * 验证一批节点在水/陆,返回 inWater[]。<b>每个节点一个独立 server.execute</b>(每 tick 主线程只加载一个
     * 小快照,避免一个 Runnable 跑多次 load 卡单 tick)。后台逐个 future.get(timeout) 单向等(不持锁、不死锁)。
     */
    private static boolean[] verifyBatchOnMainThread(ServerLevel level, List<BlockPos> batch, Budget budget) {
        boolean[] res = new boolean[batch.size()];
        for (int k = 0; k < batch.size(); k++) {
            Boolean w = verifyOneOnMainThread(level, batch.get(k), budget);
            if (w == null) {
                return null; // 超时/异常 → DEGRADE
            }
            res[k] = w;
        }
        return res;
    }

    /** 主线程乒乓验证单节点:server.execute 加载小快照查中心点可航,future.get(timeout) 单向等。 */
    private static Boolean verifyOneOnMainThread(ServerLevel level, BlockPos node, Budget budget) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        level.getServer().execute(() -> {
            try {
                RealChunkRouteWorld w = RealChunkRouteWorld.load(level, node, VERIFY_RADIUS);
                future.complete(w.isBerthWater(node.getX(), node.getZ(), null));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            Boolean r = future.get(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            budget.chunksLoaded += chunkCountFor(VERIFY_RADIUS);
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 从 gi 起找下一个验证在水的节点(逐个主线程乒乓验证);到 nodeB 还没有则返回 nodeB(末点强当好节点)。 */
    private static BlockPos findNextGoodNode(ServerLevel level, List<BlockPos> nodes, int gi, Budget budget) {
        for (int j = gi + 1; j < nodes.size() - 1; j++) {
            if (budget.chunkExceeded()) {
                return null;
            }
            boolean[] r = verifyBatchOnMainThread(level, nodes.subList(j, j + 1), budget);
            if (r == null) {
                return null;
            }
            if (r[0]) {
                return nodes.get(j);
            }
        }
        return nodes.get(nodes.size() - 1); // 末点(nodeB)强当好节点,绕行接回它
    }

    /**
     * 接力绕行:从 nGood 真实高精度寻路到 nNext;单跳预算耗光但落点在水 → 落点当新好节点继续接力,
     * 直到接回 nNext 或跳数耗尽(DEGRADE)。每跳只加载 PER_DETOUR_LOAD_RADIUS 局部快照(主线程乒乓)。
     */
    private static List<BlockPos> relayDetour(ServerLevel level, BlockPos nGood, BlockPos nNext, int seaY, Budget budget) {
        List<BlockPos> stitched = new ArrayList<>();
        stitched.add(new BlockPos(nGood.getX(), seaY, nGood.getZ()));
        BlockPos cur = nGood;
        for (int hop = 0; hop < MAX_RELAY_HOPS; hop++) {
            if (budget.chunkExceeded()) {
                return null;
            }
            // 主线程乒乓:加载 cur→nNext 包围盒的局部真实快照(以中点为心,半径覆盖跨度)。
            BlockPos center = new BlockPos((cur.getX() + nNext.getX()) / 2, seaY, (cur.getZ() + nNext.getZ()) / 2);
            int span = (int) Math.ceil(Math.sqrt(cur.distSqr(nNext)) / 2.0D) + PER_DETOUR_LOAD_RADIUS;
            RealChunkRouteWorld local = loadLocalOnMainThread(level, center, span, budget);
            if (local == null) {
                return null;
            }
            local.setTrustedBerth(cur); // 信任起点(cur 已验证在水)
            WaterRoutePolicy dp = WaterRoutePolicy.detourSegment();
            WaterRoutePathfinder pf = new WaterRoutePathfinder(local, cur, nNext, dp);
            long deadline = System.nanoTime() + (long) dp.timeoutTicks() * 50L * 1_000_000L;
            WaterRoutePathfinder.Status st;
            do {
                st = pf.step(dp.nodesPerTick(), 0);
            } while (st == WaterRoutePathfinder.Status.RUNNING && System.nanoTime() < deadline);

            if (st == WaterRoutePathfinder.Status.SUCCESS) {
                appendDedup(stitched, pf.path()); // 接回 nNext
                return stitched;
            }
            // 没接回:取前向已探索里离 nNext 最近的落点(真实可航),接力。
            List<BlockPos> partial = pf.forwardRelayLanding();
            if (partial.size() < 2) {
                return null; // 没推进 = 真绕不过
            }
            BlockPos landing = partial.get(partial.size() - 1);
            if (landing.getX() == cur.getX() && landing.getZ() == cur.getZ()) {
                return null;
            }
            appendDedup(stitched, partial);
            cur = landing; // 落水点当新好节点接力
        }
        return null; // 跳数耗尽 → DEGRADE
    }

    /** 主线程乒乓加载局部真实快照(绕行用)。超时/异常返回 null。 */
    private static RealChunkRouteWorld loadLocalOnMainThread(ServerLevel level, BlockPos center, int radius, Budget budget) {
        CompletableFuture<RealChunkRouteWorld> future = new CompletableFuture<>();
        int r = Math.min(radius, 96); // 单跳加载半径上限(同起终段快照量级;cur→nNext 跨度大靠接力多跳,不靠单跳大快照)
        level.getServer().execute(() -> {
            try {
                RealChunkRouteWorld w = RealChunkRouteWorld.load(level, center, r);
                budget.chunksLoaded += chunkCountFor(r);
                future.complete(w);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 把 src 接到 dst 尾部,首点与 dst 末点重合则跳过(去接头重复)。 */
    private static void appendDedup(List<BlockPos> dst, List<BlockPos> src) {
        if (src == null || src.isEmpty()) {
            return;
        }
        int from = 0;
        if (!dst.isEmpty()) {
            BlockPos tail = dst.get(dst.size() - 1);
            BlockPos head = src.get(0);
            if (tail.getX() == head.getX() && tail.getZ() == head.getZ()) {
                from = 1;
            }
        }
        for (int k = from; k < src.size(); k++) {
            dst.add(src.get(k));
        }
    }

    private static int indexOf(List<BlockPos> nodes, BlockPos target) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).getX() == target.getX() && nodes.get(i).getZ() == target.getZ()) {
                return i;
            }
        }
        return nodes.size() - 1;
    }

    /** 半径 radius 格的快照约覆盖多少区块(粗略,用于加载预算计数)。 */
    private static int chunkCountFor(int radius) {
        int span = (radius * 2 + 1 + 15) / 16;
        return span * span;
    }

    /** 加载/耗时预算计数。 */
    private static final class Budget {
        int chunksLoaded;

        boolean chunkExceeded() {
            return chunksLoaded > TOTAL_CHUNK_BUDGET;
        }

        boolean deadlineExceeded(long startNanos) {
            return (System.nanoTime() - startNanos) / 1_000_000L > TOTAL_VERIFY_DEADLINE_MS;
        }
    }
}
