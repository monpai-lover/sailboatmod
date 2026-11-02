package com.monpai.sailboatmod.road.generation;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ThreadPoolManager {
    private ExecutorService executor;
    private int poolSize;

    public ThreadPoolManager(int poolSize) {
        this.poolSize = poolSize;
        this.executor = Executors.newFixedThreadPool(poolSize, r -> {
            Thread t = new Thread(r, "RoadGen-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public ExecutorService getExecutor() { return executor; }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public void restart(int newPoolSize) {
        shutdown();
        this.poolSize = newPoolSize;
        this.executor = Executors.newFixedThreadPool(newPoolSize, r -> {
            Thread t = new Thread(r, "RoadGen-Worker");
            t.setDaemon(true);
            return t;
        });
    }
}
