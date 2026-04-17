package com.monpai.sailboatmod.client;

import net.minecraft.client.Minecraft;

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

public class ClaimMapRenderTaskService {
    private static final AtomicReference<ClaimMapRenderTaskService> ACTIVE = new AtomicReference<>();

    private final Executor computeExecutor;
    private final Executor mainThreadExecutor;
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong requestIds = new AtomicLong();
    private final ConcurrentHashMap<TaskKey, Long> activeRequests = new ConcurrentHashMap<>();

    public ClaimMapRenderTaskService(Executor computeExecutor, Executor mainThreadExecutor) {
        this.computeExecutor = Objects.requireNonNull(computeExecutor, "computeExecutor");
        this.mainThreadExecutor = Objects.requireNonNull(mainThreadExecutor, "mainThreadExecutor");
    }

    public static ClaimMapRenderTaskService getOrCreate() {
        ClaimMapRenderTaskService existing = ACTIVE.get();
        if (existing != null) {
            return existing;
        }
        ManagedClaimMapRenderTaskService created = new ManagedClaimMapRenderTaskService();
        if (ACTIVE.compareAndSet(null, created)) {
            return created;
        }
        created.shutdown();
        return ACTIVE.get();
    }

    public static void shutdownShared() {
        ClaimMapRenderTaskService service = ACTIVE.getAndSet(null);
        if (service instanceof ManagedClaimMapRenderTaskService managed) {
            managed.shutdown();
        } else if (service != null) {
            service.invalidateAll();
        }
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

    void invalidateAll() {
        epoch.incrementAndGet();
        activeRequests.clear();
    }

    boolean isCurrent(TaskKey key, long requestId, long submittedEpoch) {
        return epoch.get() == submittedEpoch && Objects.equals(activeRequests.get(key), requestId);
    }

    private static boolean isCancelled(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            if (cursor instanceof RenderTaskCancelledException) {
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

    public static final class RenderTaskCancelledException extends RuntimeException {
        public RenderTaskCancelledException() {
            super("Claim map render task cancelled");
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

    private static final class ManagedClaimMapRenderTaskService extends ClaimMapRenderTaskService {
        private final ExecutorService computePool;

        private ManagedClaimMapRenderTaskService() {
            this(buildExecutor(), resolveMainThreadExecutor());
        }

        private ManagedClaimMapRenderTaskService(ExecutorService computePool, Executor mainThreadExecutor) {
            super(computePool, mainThreadExecutor);
            this.computePool = computePool;
        }

        private static ExecutorService buildExecutor() {
            int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() - 1));
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "Sailboat-ClaimMapRender-" + System.nanoTime());
                thread.setDaemon(true);
                return thread;
            };
            return Executors.newFixedThreadPool(threads, factory);
        }

        private static Executor resolveMainThreadExecutor() {
            Minecraft minecraft = Minecraft.getInstance();
            return minecraft == null ? Runnable::run : minecraft::execute;
        }

        private void shutdown() {
            invalidateAll();
            computePool.shutdownNow();
        }
    }
}
