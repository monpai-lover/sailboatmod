package com.monpai.sailboatmod.route.water;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 水路寻路的后台执行器。<b>2026-06 修崩服:</b>水路寻路采样改用 {@code getBaseHeight}(每列 new NoiseChunk
 * 烘焙,比旧的单点密度慢约 1000 倍)后,若仍在主线程每 tick 推进会让单 tick 跑数秒 → ServerHangWatchdog 崩服。
 * 故把整条寻路搬到后台守护线程跑完(getBaseHeight 只读世界生成、线程安全,陆路 RoadAutoRouteService 已验证),
 * 主线程只 poll 结果。与陆路 {@code RoadPlanningTaskService} 同款:固定 2 线程 daemon 池,创建航线+卡死自救共享、
 * 自然串行排队,不与 commonPool 抢占默认异步任务。
 *
 * <p><b>可注入 Executor:</b>生产用线程池;单元测试注入 {@code Runnable::run}(同步直跑)以保持测试确定性。
 */
public final class WaterRouteTaskExecutor {
    private static volatile java.util.concurrent.Executor executor = defaultExecutor();
    private static volatile ExecutorService ownedPool;

    private WaterRouteTaskExecutor() {
    }

    private static ExecutorService defaultExecutor() {
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "WaterRoute-Worker");
            t.setDaemon(true);
            return t;
        });
        ownedPool = pool;
        return pool;
    }

    /** 在后台执行器上跑一个寻路任务,返回 CompletableFuture(异常通过 future 的 ex 分支传出,不抛到调用方)。 */
    public static <T> CompletableFuture<T> submit(Supplier<T> task) {
        return CompletableFuture.supplyAsync(task, executor);
    }

    /** 测试注入用:换成同步直跑执行器(Runnable::run),或恢复默认线程池(传 null)。 */
    public static void setExecutorForTesting(java.util.concurrent.Executor injected) {
        executor = injected != null ? injected : defaultExecutor();
    }

    /** 服务器关闭时优雅停掉自有线程池(注入的外部 executor 不归我管)。 */
    public static void shutdown() {
        ExecutorService pool = ownedPool;
        if (pool == null) {
            return;
        }
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
