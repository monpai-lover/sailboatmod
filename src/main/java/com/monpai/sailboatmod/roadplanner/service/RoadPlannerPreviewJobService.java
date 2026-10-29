package com.monpai.sailboatmod.roadplanner.service;

import com.mojang.logging.LogUtils;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One road preview per player. Streaming requests capture owned data on the tick, predict one worker window,
 * and publish on the tick. The older bounded warmup entrypoint remains for existing internal callers.
 */
public final class RoadPlannerPreviewJobService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RoadPlannerPreviewJobService GLOBAL = new RoadPlannerPreviewJobService();
    static final int TIMEOUT_TICKS = 20 * 120;
    private static final int PROGRESS_EVERY_TICKS = 20;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();
    private final Map<UUID, StreamingJob> streaming = new ConcurrentHashMap<>();
    private ThreadPoolExecutor terrainWorker;

    public static RoadPlannerPreviewJobService global() {
        return GLOBAL;
    }

    /** Replaces any job the owner already has. Runs right away when every chunk is already loaded. */
    public void submit(UUID owner, ResourceKey<Level> dimension, RoadChunkWarmup warmup, Runnable onReady, Consumer<String> notify) {
        cancel(owner);
        Job job = new Job(dimension, warmup, onReady, notify);
        if (warmup.tick() == RoadChunkWarmup.Status.READY) {
            finish(job);
            return;
        }
        jobs.put(owner, job);
    }

    public boolean cancel(UUID owner) {
        Job job = jobs.remove(owner);
        StreamingJob stream = streaming.remove(owner);
        if (job != null) job.warmup.release();
        if (stream != null) {
            stream.work.cancel();
            if (terrainWorker != null) terrainWorker.purge();
        }
        return job != null || stream != null;
    }

    /** Drops every job and frees its chunks (server stop: the static map would otherwise outlive the world). */
    public void clear() {
        for (UUID owner : new ArrayList<>(jobs.keySet())) {
            cancel(owner);
        }
        for (UUID owner : new ArrayList<>(streaming.keySet())) cancel(owner);
        if (terrainWorker != null) { terrainWorker.shutdownNow(); terrainWorker = null; }
    }

    public boolean pendingFor(UUID owner) {
        return jobs.containsKey(owner) || streaming.containsKey(owner);
    }

    /** The existing preview lifecycle also owns streaming progress, replacement, logout and cancellation. */
    public void submitStreaming(UUID owner, ResourceKey<Level> dimension, RoadPlannerStagedPreviewJob work,
                                Consumer<SyncRoadPlannerPreviewPacket> publish, Runnable ready, Consumer<String> notify) {
        cancel(owner);
        if (streaming.size() >= 8) {
            work.cancel();
            notify.accept("道路规划繁忙，请稍后重试。");
            return;
        }
        streaming.put(owner, new StreamingJob(dimension, work, publish, ready, notify));
    }

    public Executor terrainWorker() {
        if (terrainWorker == null || terrainWorker.isShutdown()) {
            terrainWorker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
                Thread thread = new Thread(task, "RoadPlanner-Terrain");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        }
        return terrainWorker;
    }

    public void tick(ResourceKey<Level> dimension, Predicate<UUID> ownerOnline) {
        List<UUID> owners = new ArrayList<>(jobs.keySet());
        for (UUID owner : owners) {
            Job job = jobs.get(owner);
            if (job == null || !job.dimension.equals(dimension)) {
                continue;
            }
            if (!ownerOnline.test(owner)) {
                cancel(owner);
                continue;
            }
            job.age++;
            if (job.warmup.tick() == RoadChunkWarmup.Status.READY) {
                jobs.remove(owner, job);
                finish(job);
            } else if (job.age >= TIMEOUT_TICKS) {
                cancel(owner);
                job.notify.accept("道路沿线区块加载超时（已加载 " + job.warmup.loaded() + "/" + job.warmup.total() + "），请稍后重试或缩短路线。");
            } else if (job.age % PROGRESS_EVERY_TICKS == 0) {
                job.notify.accept("正在加载道路沿线区块 " + job.warmup.loaded() + "/" + job.warmup.total());
            }
        }
        for (Map.Entry<UUID, StreamingJob> entry : new ArrayList<>(streaming.entrySet())) {
            UUID owner = entry.getKey();
            StreamingJob job = entry.getValue();
            if (!job.dimension.equals(dimension)) continue;
            if (!ownerOnline.test(owner)) { cancel(owner); continue; }
            int before = job.work.completedStages();
            try {
                job.work.tick(job.publish);
                if (job.work.isDone()) {
                    streaming.remove(owner, job);
                    job.ready.run();
                    continue;
                }
            } catch (RuntimeException unavailable) {
                cancel(owner);
                LOGGER.warn("Road preview terrain unavailable: {}", unavailable.toString());
                job.notify.accept("道路地形数据暂不可用，规划已停止：" + unavailable.getMessage());
                continue;
            }
            job.idleTicks = before == job.work.completedStages() ? job.idleTicks + 1 : 0;
            if (job.idleTicks >= TIMEOUT_TICKS) {
                cancel(owner);
                job.notify.accept("道路当前采样段等待超时，请检查地形数据后重试。");
            } else if (++job.age % PROGRESS_EVERY_TICKS == 0) {
                job.notify.accept("正在分段规划道路，已完成 " + job.work.completedStages() + " 段。");
            }
        }
    }

    private static final class StreamingJob {
        final ResourceKey<Level> dimension;
        final RoadPlannerStagedPreviewJob work;
        final Consumer<SyncRoadPlannerPreviewPacket> publish;
        final Runnable ready;
        final Consumer<String> notify;
        int idleTicks, age;
        StreamingJob(ResourceKey<Level> dimension, RoadPlannerStagedPreviewJob work,
                     Consumer<SyncRoadPlannerPreviewPacket> publish, Runnable ready, Consumer<String> notify) {
            this.dimension = dimension; this.work = work; this.publish = publish; this.ready = ready; this.notify = notify;
        }
    }

    private static void finish(Job job) {
        try {
            job.onReady.run();
        } catch (RuntimeException e) {
            LOGGER.error("Road preview failed", e);
            job.notify.accept("道路预览计算失败：" + e.getMessage());
        } finally {
            job.warmup.release();
        }
    }

    private static final class Job {
        final ResourceKey<Level> dimension;
        final RoadChunkWarmup warmup;
        final Runnable onReady;
        final Consumer<String> notify;
        int age;

        Job(ResourceKey<Level> dimension, RoadChunkWarmup warmup, Runnable onReady, Consumer<String> notify) {
            this.dimension = dimension;
            this.warmup = warmup;
            this.onReady = onReady;
            this.notify = notify;
        }
    }
}
