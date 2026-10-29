package com.monpai.sailboatmod.roadplanner.service;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * One road preview per player: waits for the chunks along the road to load (see {@link RoadChunkWarmup}),
 * then computes the preview on the main thread and frees the chunks.
 */
public final class RoadPlannerPreviewJobService {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final RoadPlannerPreviewJobService GLOBAL = new RoadPlannerPreviewJobService();
    static final int TIMEOUT_TICKS = 20 * 120;
    private static final int PROGRESS_EVERY_TICKS = 20;

    private final Map<UUID, Job> jobs = new ConcurrentHashMap<>();

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
        if (job == null) {
            return false;
        }
        job.warmup.release();
        return true;
    }

    /** Drops every job and frees its chunks (server stop: the static map would otherwise outlive the world). */
    public void clear() {
        for (UUID owner : new ArrayList<>(jobs.keySet())) {
            cancel(owner);
        }
    }

    public boolean pendingFor(UUID owner) {
        return jobs.containsKey(owner);
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
