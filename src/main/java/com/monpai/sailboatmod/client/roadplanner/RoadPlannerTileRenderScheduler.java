package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.world.level.ChunkPos;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class RoadPlannerTileRenderScheduler implements AutoCloseable {
    private final Set<Long> submitted = ConcurrentHashMap.newKeySet();
    private final ExecutorService executor;
    private volatile boolean closed;

    public RoadPlannerTileRenderScheduler() {
        this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger();

            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, "road-planner-tile-render-" + index.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        });
    }

    public boolean markSubmitted(ChunkPos chunkPos) {
        if (closed || chunkPos == null) {
            return false;
        }
        return submitted.add(chunkPos.toLong());
    }

    public boolean submit(ChunkPos chunkPos, Runnable task) {
        if (!markSubmitted(chunkPos)) {
            return false;
        }
        executor.execute(() -> {
            try {
                if (!closed && task != null) {
                    task.run();
                }
            } catch (Exception ignored) {
            }
        });
        return true;
    }

    public boolean alreadySubmitted(ChunkPos chunkPos) {
        return !closed && chunkPos != null && submitted.contains(chunkPos.toLong());
    }

    public void clear() {
        submitted.clear();
    }

    @Override
    public void close() {
        closed = true;
        submitted.clear();
        executor.shutdownNow();
    }
}
