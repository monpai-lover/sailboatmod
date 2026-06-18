package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

public final class WaterRouteTask {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final String dimensionId;
    private final BlockPos sourceDockPos;
    private final BlockPos targetDockPos;
    private final BlockPos sourceBerthPos;
    private final BlockPos targetBerthPos;
    private final String requesterName;
    private final WaterRoutePolicy policy;
    private final WaterRoutePathfinder pathfinder;
    private final CompletionHandler completionHandler;
    private final String key;
    private boolean completed;
    // 异步状态机(修崩服):寻路在后台线程跑,主线程 tick 只 poll。
    // asyncStarted:后台搜索已提交;asyncResult:后台跑完写入(volatile,主线程读)——必为非 null 才算完成。
    private volatile boolean asyncStarted;
    private volatile WaterRouteResult<List<BlockPos>> asyncResult;

    public WaterRouteTask(String dimensionId,
                          BlockPos sourceDockPos,
                          BlockPos targetDockPos,
                          BlockPos sourceBerthPos,
                          BlockPos targetBerthPos,
                          String requesterName,
                          WaterRoutePolicy policy,
                          WaterRoutePathfinder pathfinder,
                          CompletionHandler completionHandler) {
        this.dimensionId = dimensionId == null ? "" : dimensionId;
        this.sourceDockPos = sourceDockPos;
        this.targetDockPos = targetDockPos;
        this.sourceBerthPos = sourceBerthPos;
        this.targetBerthPos = targetBerthPos;
        this.requesterName = requesterName == null ? "" : requesterName;
        this.policy = policy == null ? WaterRoutePolicy.defaults() : policy;
        this.pathfinder = Objects.requireNonNull(pathfinder, "pathfinder");
        this.completionHandler = completionHandler;
        this.key = key(this.dimensionId, sourceDockPos, targetDockPos);
    }

    /**
     * 主线程每 tick 调用。<b>2026-06 异步化(修崩服):</b>不再在主线程 step(每次 step 上千次 getBaseHeight →
     * new NoiseChunk → 单 tick 数秒崩服)。首次 advance 把整条寻路提交后台线程跑完,之后每 tick 只 poll
     * {@code asyncResult}(volatile,后台写完为非 null)。完成时在<b>主线程</b>调 completionHandler →
     * applyCompletedRoute 里的 RealWaterVerifier/setRoutes 天然在主线程,无需手动切线程。
     *
     * @return true 表示本 task 已完成(可从 pending 移除)。
     */
    boolean advance() {
        if (completed) {
            return true;
        }
        if (!asyncStarted) {
            asyncStarted = true;
            startBackgroundSearch();
            return false; // 后台刚启动,这一 tick 还没结果
        }
        WaterRouteResult<List<BlockPos>> result = asyncResult; // 单次 volatile 读
        if (result == null) {
            return false; // 后台仍在跑(不占主线程 CPU)
        }
        complete(result); // 主线程调 completionHandler
        return true;
    }

    /**
     * 把寻路提交后台线程,跑到 SUCCESS/FAILED 或 wall-clock 超时。<b>双保险必写非 null asyncResult</b>
     * (try/catch Throwable + whenComplete ex 分支),否则后台异常会让 task 永久 pending。
     * 超时用 wall-clock(timeoutTicks×50ms,复用 policy 字段);maxExpandedNodes 节点上限在 step 内部硬兜底。
     */
    private void startBackgroundSearch() {
        final long startNanos = System.nanoTime();
        final long timeoutMs = (long) policy.timeoutTicks() * 50L;
        final long deadlineNanos = startNanos + timeoutMs * 1_000_000L;
        LOGGER.info("[WaterPath] 后台寻路提交 key={} 超时={}s nodesPerTick={} maxNodes={}",
                key, timeoutMs / 1000.0, policy.nodesPerTick(), policy.maxExpandedNodes());
        WaterRouteTaskExecutor.submit(() -> {
            try {
                WaterRoutePathfinder.Status status;
                do {
                    status = pathfinder.step(policy.nodesPerTick(), policy.chunkLoadsPerTick());
                } while (status == WaterRoutePathfinder.Status.RUNNING
                        && System.nanoTime() < deadlineNanos);
                long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
                if (status == WaterRoutePathfinder.Status.SUCCESS) {
                    List<BlockPos> p = pathfinder.path();
                    LOGGER.info("[WaterPath] 后台寻路成功 key={} 耗时={}ms 节点={} 航点={}",
                            key, elapsedMs, pathfinder.expandedNodes(), p == null ? 0 : p.size());
                    return WaterRouteResult.success(p);
                }
                if (status == WaterRoutePathfinder.Status.FAILED) {
                    LOGGER.warn("[WaterPath] 后台寻路失败 key={} 耗时={}ms 节点={} 原因={}",
                            key, elapsedMs, pathfinder.expandedNodes(), pathfinder.failureReason());
                    return WaterRouteResult.<List<BlockPos>>failure(pathfinder.failureReason());
                }
                // RUNNING 但跳出循环 = wall-clock 超时(节点没耗尽,纯是太慢跑不完)。
                LOGGER.warn("[WaterPath] 后台寻路超时 key={} 耗时={}ms 节点={}/{} (仍 RUNNING,航线太长/采样太慢)",
                        key, elapsedMs, pathfinder.expandedNodes(), policy.maxExpandedNodes());
                return WaterRouteResult.<List<BlockPos>>failure(WaterRouteFailureReason.TIMEOUT);
            } catch (Throwable t) {
                LOGGER.error("[WaterPath] 后台寻路异常 key={}", key, t);
                return WaterRouteResult.<List<BlockPos>>failure(WaterRouteFailureReason.NO_WATER_PATH);
            }
        }).whenComplete((res, ex) ->
                // 必写非 null,否则 task 永久 pending。try/catch 已兜住绝大多数异常,ex 是最后保险。
                asyncResult = (ex != null || res == null)
                        ? WaterRouteResult.<List<BlockPos>>failure(WaterRouteFailureReason.NO_WATER_PATH)
                        : res);
    }

    private void complete(WaterRouteResult<List<BlockPos>> result) {
        if (completed) {
            return;
        }
        completed = true;
        if (completionHandler != null) {
            completionHandler.complete(result);
        }
    }

    public String dimensionId() {
        return dimensionId;
    }

    public BlockPos sourceDockPos() {
        return sourceDockPos;
    }

    public BlockPos targetDockPos() {
        return targetDockPos;
    }

    public BlockPos sourceBerthPos() {
        return sourceBerthPos;
    }

    public BlockPos targetBerthPos() {
        return targetBerthPos;
    }

    public String requesterName() {
        return requesterName;
    }

    public String key() {
        return key;
    }

    public boolean completed() {
        return completed;
    }

    private static String key(String dimensionId, BlockPos sourceDockPos, BlockPos targetDockPos) {
        return dimensionId + "|" + posKey(sourceDockPos) + "|" + posKey(targetDockPos);
    }

    private static String posKey(BlockPos pos) {
        return pos == null ? "null" : Long.toString(pos.asLong());
    }

    @FunctionalInterface
    public interface CompletionHandler {
        void complete(WaterRouteResult<List<BlockPos>> result);
    }
}
