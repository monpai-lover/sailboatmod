package com.monpai.sailboatmod.nation.service;

import net.minecraft.server.MinecraftServer;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

public class ClaimMapTaskService {
    private static final AtomicReference<ClaimMapTaskService> ACTIVE = new AtomicReference<>();

    private final Executor computeExecutor;
    private final Executor mainThreadExecutor;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<TaskKey, Long> activeRequests = new ConcurrentHashMap<>();

    public ClaimMapTaskService(Executor computeExecutor, Executor mainThreadExecutor) {
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public static void onServerStarted(MinecraftServer server) {
        if (server == null) {
            return;
        }
        ACTIVE.getAndUpdate(existing -> {
            if (existing instanceof ManagedClaimMapTaskService managed) {
                managed.shutdown();
            }
            return new ManagedClaimMapTaskService(server);
        });
    }

    public static void onServerStopping() {
        ClaimMapTaskService service = ACTIVE.getAndSet(null);
        if (service instanceof ManagedClaimMapTaskService managed) {
            managed.shutdown();
        } else if (service != null) {
            service.invalidateAllForTest();
        }
    }

    public static ClaimMapTaskService get() {
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
                .handle((result, throwable) -> {
                    if (throwable == null) {
                        return result;
                    }
                    if (isCancelled(throwable)) {
                        return null;
                    }
                    throw throwable instanceof RuntimeException runtime
                            ? runtime
                            : new CompletionException(throwable);
                })
                .thenApply(result -> isCurrent(key, requestId, submittedEpoch) ? result : null)
                .thenApplyAsync(result -> {
                    if (result != null && isCurrent(key, requestId, submittedEpoch)) {
                        apply.accept(result);
                    }
                    return result;
                }, mainThreadExecutor);
    }

    <T> TaskHandle<T> submitForTest(TaskKey key, Supplier<T> supplier, Consumer<T> apply) {
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

    boolean isCurrent(TaskKey key, long requestId, long submittedEpoch) {
        return epoch.get() == submittedEpoch && Objects.equals(activeRequests.get(key), requestId);
    }

    private static boolean isCancelled(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof ClaimMapTaskCancelledException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    public record TaskKey(String kind, String ownerKey) {
        public TaskKey {
            kind = kind == null ? "" : kind;
            ownerKey = ownerKey == null ? "" : ownerKey;
        }
    }

    public static final class ClaimMapTaskCancelledException extends RuntimeException {
        public ClaimMapTaskCancelledException() {
            super("Claim map task cancelled");
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

    private static final class ManagedClaimMapTaskService extends ClaimMapTaskService {
        private final ExecutorService computePool;

        private ManagedClaimMapTaskService(MinecraftServer server) {
            this(buildExecutor(), server::execute);
        }

        private ManagedClaimMapTaskService(ExecutorService computePool, Executor mainThreadExecutor) {
            super(computePool, mainThreadExecutor);
            this.computePool = computePool;
        }

        private static ExecutorService buildExecutor() {
            int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "Sailboat-ClaimMap-" + System.nanoTime());
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
