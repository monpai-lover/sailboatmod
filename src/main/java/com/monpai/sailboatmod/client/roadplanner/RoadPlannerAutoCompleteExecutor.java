package com.monpai.sailboatmod.client.roadplanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Two bounded worker slots. A request remains owned until the main-thread delivery executes.
 * Replacing or cancelling a queued request removes its FutureTask from the queue immediately.
 * No CallerRuns fallback and no stale callback after logout, dimension change or replacement.
 */
public final class RoadPlannerAutoCompleteExecutor {
    private static final Logger LOGGER = LoggerFactory.getLogger("RoadPlannerAuto");
    private static final Engine GLOBAL = new Engine(2, 16);
    private RoadPlannerAutoCompleteExecutor() { }
    public static <T> void submit(UUID owner, Supplier<T> work, Consumer<Runnable> deliver,
                                  Consumer<T> onResult, Runnable busy) {
        GLOBAL.submit(owner, work, deliver, onResult, busy);
    }
    public static void cancel(UUID owner) { GLOBAL.cancel(owner); }
    public static void cancelAll() { GLOBAL.cancelAll(); }
    static int queued() { return GLOBAL.queued(); }

    static final class Engine implements AutoCloseable {
        private final ThreadPoolExecutor pool;
        private final Map<UUID, Job<?>> owners = new HashMap<>();
        private static final class Job<T> { FutureTask<Void> future; }
        Engine(int workers, int capacity) {
            pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(capacity), runnable -> {
                Thread thread = new Thread(runnable, "sailboat-road-autocomplete");
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            pool.allowCoreThreadTimeOut(true);
        }
        <T> void submit(UUID owner, Supplier<T> work, Consumer<Runnable> deliver,
                        Consumer<T> onResult, Runnable busy) {
            Objects.requireNonNull(owner); Objects.requireNonNull(work); Objects.requireNonNull(deliver);
            Job<T> job = new Job<>();
            job.future = new FutureTask<>(() -> {
                T result;
                try { result = work.get(); }
                catch (CancellationException cancelled) { remove(owner, job); return null; }
                catch (RuntimeException failure) { LOGGER.warn("[RoadPlan] 自动补全后台失败", failure); result = null; }
                T value = result;
                try {
                    deliver.accept(() -> {
                        // Crucially re-check HERE, not just before scheduling server.execute.
                        if (remove(owner, job)) onResult.accept(value);
                    });
                } catch (RuntimeException stopped) { remove(owner, job); }
                return null;
            });
            boolean rejected = false;
            synchronized (owners) {
                Job<?> old = owners.put(owner, job);
                if (old != null) { old.future.cancel(true); pool.remove(old.future); }
                try { pool.execute(job.future); }
                catch (RejectedExecutionException full) { rejected = true; }
            }
            if (rejected) {
                job.future.cancel(false);
                try { deliver.accept(() -> { if (remove(owner, job)) busy.run(); }); }
                catch (RuntimeException stopped) { remove(owner, job); }
            }
        }
        private boolean remove(UUID owner, Job<?> job) {
            synchronized (owners) { return owners.remove(owner, job); }
        }
        void cancel(UUID owner) {
            synchronized (owners) {
                Job<?> job = owners.remove(owner);
                if (job != null) { job.future.cancel(true); pool.remove(job.future); }
            }
        }
        void cancelAll() {
            synchronized (owners) {
                for (Job<?> job : owners.values()) { job.future.cancel(true); pool.remove(job.future); }
                owners.clear();
            }
        }
        int queued() { return pool.getQueue().size() + pool.getActiveCount(); }
        int retained() { synchronized (owners) { return owners.size(); } }
        @Override public void close() { cancelAll(); pool.shutdownNow(); }
    }
}
