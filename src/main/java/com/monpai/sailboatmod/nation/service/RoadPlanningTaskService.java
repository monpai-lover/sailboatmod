package com.monpai.sailboatmod.nation.service;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class RoadPlanningTaskService {
    private static final AtomicReference<RoadPlanningTaskService> ACTIVE = new AtomicReference<>();

    private final Executor computeExecutor;
    private final Executor mainThreadExecutor;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<TaskKey, Long> activeRequests = new ConcurrentHashMap<>();

    public RoadPlanningTaskService(Executor computeExecutor, Executor mainThreadExecutor) {
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ACTIVE.getAndUpdate(existing -> {
            if (existing instanceof ManagedRoadPlanningTaskService managed) {
                managed.shutdown();
            }
            return new ManagedRoadPlanningTaskService(server);
        });
    }

    public static void onServerStopping() {
        RoadPlanningTaskService service = ACTIVE.getAndSet(null);
        if (service instanceof ManagedRoadPlanningTaskService managed) {
            managed.shutdown();
        } else if (service != null) {
            service.invalidateAllForTest();
        }
    }

    public static RoadPlanningTaskService get() {
        return ACTIVE.get();
    }

    public <T> CompletableFuture<T> submitLatest(TaskKey key, Supplier<T> supplier, Consumer<T> apply) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(apply, "apply");

        long requestId = requestIds.incrementAndGet();
        long submittedEpoch = epoch.get();
        activeRequests.put(key, requestId);

        return CompletableFuture.supplyAsync(supplier, computeExecutor)
                .thenApply(result -> {
                    if (!isCurrent(key, requestId, submittedEpoch)) {
                        return null;
                    }
                    return result;
                })
                .thenApplyAsync(result -> {
                    if (result != null && isCurrent(key, requestId, submittedEpoch)) {
                        apply.accept(result);
                    }
                    return result;
                }, mainThreadExecutor);
    }

    TaskHandle<String> submitForTest(TaskKey key, Supplier<String> supplier, Consumer<String> apply) {
        return submitForTestInternal(key, supplier, apply);
    }

    <T> TaskHandle<T> submitForTestInternal(TaskKey key, Supplier<T> supplier, Consumer<T> apply) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(apply, "apply");

        long requestId = requestIds.incrementAndGet();
        long submittedEpoch = epoch.get();
        activeRequests.put(key, requestId);
        return new TaskHandle<>(supplier, apply, key, requestId, submittedEpoch);
    }

    void invalidateAllForTest() {
        epoch.incrementAndGet();
        activeRequests.clear();
    }

    private boolean isCurrent(TaskKey key, long requestId, long submittedEpoch) {
        return epoch.get() == submittedEpoch && Objects.equals(activeRequests.get(key), requestId);
    }

    public record TaskKey(String kind, String ownerKey) {
        public TaskKey {
            kind = kind == null ? "" : kind;
            ownerKey = ownerKey == null ? "" : ownerKey;
        }
    }

    public final class TaskHandle<T> {
        private final Supplier<T> supplier;
        private final Consumer<T> apply;
        private final TaskKey key;
        private final long requestId;
        private final long submittedEpoch;

        private TaskHandle(Supplier<T> supplier, Consumer<T> apply, TaskKey key, long requestId, long submittedEpoch) {
            this.supplier = supplier;
            this.apply = apply;
            this.key = key;
            this.requestId = requestId;
            this.submittedEpoch = submittedEpoch;
        }

        public void completeForTest() {
            T value = supplier.get();
            if (isCurrent(key, requestId, submittedEpoch)) {
                apply.accept(value);
            }
        }
    }

    private static final class ManagedRoadPlanningTaskService extends RoadPlanningTaskService {
        private final ExecutorService computePool;

        private ManagedRoadPlanningTaskService(MinecraftServer server) {
            this(buildExecutor(), server::execute);
        }

        private ManagedRoadPlanningTaskService(ExecutorService computePool, Executor mainThreadExecutor) {
            super(computePool, mainThreadExecutor);
            this.computePool = computePool;
        }

        private static ExecutorService buildExecutor() {
            int threads = Math.max(1, Runtime.getRuntime().availableProcessors() - 1);
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "Sailboat-RoadPlanning-" + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            };
            return Executors.newFixedThreadPool(threads, factory);
        }

        private void shutdown() {
            invalidateAllForTest();
            computePool.shutdownNow();
        }
    }
}
