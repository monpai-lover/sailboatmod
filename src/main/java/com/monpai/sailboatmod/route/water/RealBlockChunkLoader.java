package com.monpai.sailboatmod.route.water;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 真实区块批加载器:把一批区块填进 {@link RealBlockWaterMap},两阶段:
 * <ol>
 *   <li><b>NBT 阶段(后台,主力)</b>:后台 worker 直接 {@code chunkMap.read}+{@code future.get} 读磁盘 NBT,
 *       <b>完全不碰主线程、不卡服</b>。航行过的区域都走这层([[chunkmap_read_offthread_nbt]])。</li>
 *   <li><b>force 阶段(主线程,兜底)</b>:NBT 读不到的极少区块(真未存盘),主线程乒乓节流 force——每 tick 至多
 *       {@link #MAX_FORCED_PER_TICK} 个,单 server.execute 占满 {@link #TICK_BUDGET_MS} 就停、剩余下 tick → 不卡服。</li>
 * </ol>
 * <b>不死锁</b>:NBT 的 chunkMap.read future 在磁盘 IO 线程完成(不 join 主线程);force 走 server.execute 单向投递。
 */
public final class RealBlockChunkLoader {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final long TICK_BUDGET_MS = 20;     // 单个 force 主线程 Runnable 的 wall-clock 上限
    private static final int MAX_FORCED_PER_TICK = 1;  // 单 tick 只 force 1 个(getChunk(FULL) 对未生成区块可能几百ms)
    private static final long FORCE_BATCH_TIMEOUT_MS = 15000;
    // force 批之间后台 sleep,把 force 摊到很长时间,主线程每次只被占一下就放开(慢可以,不卡服)。
    private static final long FORCE_GAP_MS = 40;        // 每 force 1 个后 sleep 这么久(让主线程跑几个正常 tick)

    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(int done, int total);
    }

    private final ServerLevel level;
    private final RealBlockWaterMap map;

    public RealBlockChunkLoader(ServerLevel level, RealBlockWaterMap map) {
        this.level = level;
        this.map = map;
    }

    /**
     * 后台调用:把 chunks 全部填进水图(已缓存跳过)。NBT 后台读(快、不卡),失败的区块主线程节流 force。带进度。
     */
    public void loadAll(List<ChunkPos> chunks, ProgressCallback progress, long deadlineMs) {
        if (level == null || level.getServer() == null || chunks == null || chunks.isEmpty()) {
            return;
        }
        long t0 = System.nanoTime();
        // ---- 阶段1:NBT 后台读(主力,不碰主线程)----
        List<ChunkPos> needForce = new ArrayList<>();
        int total = chunks.size();
        int done = 0;
        for (ChunkPos cp : chunks) {
            if ((System.nanoTime() - t0) / 1_000_000L > deadlineMs) {
                LOGGER.warn("[WaterPath] 真实区块加载超 {}ms,剩 {} 区块未读(缝隙保守判陆)", deadlineMs, total - done);
                break;
            }
            if (!map.loadNbtOffThread(cp.x, cp.z)) {
                needForce.add(cp); // NBT 磁盘上没有 → 留给 force 兜底
            }
            done++;
            if (progress != null && (done & 31) == 0) {
                progress.onProgress(done, total);
            }
        }
        // ---- 阶段2:force 兜底(主线程节流,仅 NBT 读不到的少数)----
        if (!needForce.isEmpty()) {
            LOGGER.info("[WaterPath] NBT 读到 {}/{} 区块,force 兜底 {} 区块(未存盘)", total - needForce.size(), total, needForce.size());
            forceAll(needForce, t0, deadlineMs);
        }
        if (progress != null) {
            progress.onProgress(total, total);
        }
    }

    /** force 兜底批:主线程节流加载(每 tick ≤ MAX_FORCED_PER_TICK,单 Runnable 占满 TICK_BUDGET 就停)。 */
    private void forceAll(List<ChunkPos> chunks, long t0, long deadlineMs) {
        Deque<ChunkPos> queue = new ArrayDeque<>(chunks);
        while (!queue.isEmpty()) {
            if ((System.nanoTime() - t0) / 1_000_000L > deadlineMs) {
                LOGGER.warn("[WaterPath] force 兜底超时,剩 {} 区块未加载(保守判陆)", queue.size());
                break;
            }
            ChunkPos[] batch = new ChunkPos[Math.min(MAX_FORCED_PER_TICK, queue.size())];
            for (int i = 0; i < batch.length; i++) {
                batch[i] = queue.poll();
            }
            int processed = runForceBatchOnMainThread(batch);
            for (int i = processed; i < batch.length; i++) {
                queue.addFirst(batch[i]); // 未处理回退(单 tick 预算用尽)
            }
            // force 批之间后台 sleep,把 force 摊到很长时间(慢可以),主线程每次只被占一下就放开 → 不卡服。
            if (!queue.isEmpty()) {
                try {
                    Thread.sleep(FORCE_GAP_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    /** 一批 force 投主线程;主线程预算内逐个,超 TICK_BUDGET 把剩余 requeue。返回实际处理数。 */
    private int runForceBatchOnMainThread(ChunkPos[] batch) {
        CompletableFuture<Integer> future = new CompletableFuture<>();
        level.getServer().execute(() -> {
            long s0 = System.nanoTime();
            int processed = 0;
            try {
                for (int i = 0; i < batch.length; i++) {
                    if (i > 0 && (System.nanoTime() - s0) / 1_000_000L >= TICK_BUDGET_MS) {
                        break; // 单 tick 预算用尽(至少处理 1 个,避免死循环)
                    }
                    map.loadForcedOnMainThread(batch[i].x, batch[i].z);
                    processed++;
                }
            } catch (Throwable ignored) {
            }
            future.complete(processed);
        });
        try {
            return future.get(FORCE_BATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (Throwable t) {
            LOGGER.warn("[WaterPath] force 批乒乓超时,跳过 {} 区块", batch.length);
            return batch.length; // 跳过(保守判陆),不 requeue 避免死循环
        }
    }
}
