package com.monpai.sailboatmod.nation.service;

import net.minecraft.server.MinecraftServer;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class RoadPlanningTaskService {
    private static volatile RoadPlanningTaskService INSTANCE;
    private final ExecutorService executor;
    private final Map<TaskKey, CompletableFuture<?>> activeTasks = new ConcurrentHashMap<>();

    private RoadPlanningTaskService() {
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "RoadPlanning-Worker");
            t.setDaemon(true);
            return t;
        });
    }

    public record TaskKey(String category, String id) {}

    public static RoadPlanningTaskService get() {
        if (INSTANCE == null) {
            synchronized (RoadPlanningTaskService.class) {
                if (INSTANCE == null) {
                    INSTANCE = new RoadPlanningTaskService();
                }
            }
        }
        return INSTANCE;
    }

    public static void onServerStarted(MinecraftServer server) {
        get();
    }

    public static void onServerStopping() {
        RoadPlanningTaskService inst = INSTANCE;
        if (inst != null) {
            inst.shutdown();
            INSTANCE = null;
        }
    }

    public static void throwIfCancelled() {
        if (Thread.currentThread().isInterrupted()) {
            throw new java.util.concurrent.CancellationException("Task cancelled");
        }
    }

    public <T> CompletableFuture<T> submitLatest(TaskKey key, Supplier<T> task, Consumer<T> callback) {
        CompletableFuture<?> prev = activeTasks.get(key);
        if (prev != null && !prev.isDone()) {
            prev.cancel(true);
        }
        CompletableFuture<T> future = CompletableFuture.supplyAsync(task, executor)
            .whenComplete((result, ex) -> {
                activeTasks.remove(key);
                if (ex == null && callback != null) {
                    callback.accept(result);
                }
            });
        activeTasks.put(key, future);
        return future;
    }

    private void shutdown() {
        for (CompletableFuture<?> f : activeTasks.values()) {
            f.cancel(true);
        }
        activeTasks.clear();
        executor.shutdown();
        try {
            if (!executor.awaitTermination(3, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
