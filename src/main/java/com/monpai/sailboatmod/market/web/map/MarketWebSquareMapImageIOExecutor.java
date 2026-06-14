package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Bounded single-thread image writer, adapted from squaremap's ImageIOExecutor model.
 */
public final class MarketWebSquareMapImageIOExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int IMAGE_IO_MAX_TASKS = 100;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SailboatMarketWebMap-ImageIO");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicLong submittedTasks = new AtomicLong();
    private final AtomicLong executedTasks = new AtomicLong();

    public void submit(Runnable task) {
        if (task == null || executor.isShutdown()) {
            return;
        }
        submittedTasks.incrementAndGet();
        executor.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException exception) {
                LOGGER.warn("Market web square map image write failed", exception);
            } finally {
                executedTasks.incrementAndGet();
            }
        });
        throttleIfBehind();
    }

    public int pendingTasks() {
        return (int) Math.max(0L, submittedTasks.get() - executedTasks.get());
    }

    public void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(5L, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private void throttleIfBehind() {
        for (int failures = 1; pendingTasks() >= IMAGE_IO_MAX_TASKS; failures++) {
            boolean interrupted = Thread.interrupted();
            Thread.yield();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(Math.min(25, failures)));
            if (interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
