EDITS = [
('src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteExecutor.java', None, 'e3afd6ef6d8d0cc22fd688d0914ba39c1e1a0542afe8dc21300eb202d2e8489e', [
(0, 0, r"""package com.monpai.sailboatmod.client.roadplanner;

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
""")
]),
('src/main/java/com/monpai/sailboatmod/client/roadplanner/RoadPlannerAutoCompleteService.java', 'd0656a3bc1d35e34f08e6ccc13312d060c0948df996141a9728d66ddca13e72e', 'c8c996b680b7b7f5c3b9fa03f9579420644634e0657d21b81372300013704ae1', [
(3, 3, r"""import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import java.util.concurrent.CancellationException;
"""),
(12, 12, r"""    private final boolean terrainAware;
    static final int MAX_OUTPUT_NODES = 16_384;
    private java.util.function.Supplier<String> planningNote = () -> "";

    RoadPlannerAutoCompleteService withPlanningNote(java.util.function.Supplier<String> note) {
        planningNote = java.util.Objects.requireNonNull(note);
        return this;
    }
"""),
(37, 37, r"""        this.terrainAware = landProbe != null;
"""),
(45, 45, r"""        RoadSearchBudget.check();
        if (manualNodes != null && manualNodes.size() > 1024)
            return RoadPlannerAutoCompleteResult.failure("手工节点过多，请分段规划");
"""),
(54, 54, r"""        RoadSearchBudget.check();
"""),
(55, 55, r"""            if (pathfinderRunner != null)
                return RoadPlannerAutoCompleteResult.failure("道路寻路失败或超时，未生成直线兜底");
"""),
(61, 61, r"""        if (suffixNodes.size() > 65_536)
            return RoadPlannerAutoCompleteResult.failure("道路节点过多，请分段规划");
"""),
(62, 62, r"""        double length = 0;
        for (int i = 1; i < suffixNodes.size(); i++) {
            RoadSearchBudget.check();
            BlockPos a = suffixNodes.get(i-1), b = suffixNodes.get(i);
            length += Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
        }
        if (length > 1_000_000) return RoadPlannerAutoCompleteResult.failure("道路过长，请分段规划");
        spacing = Math.max(spacing, (int)Math.ceil(length / (MAX_OUTPUT_NODES / 2.0)));
        List<BlockPos> rawNodes = suffixNodes;
"""),
(66, 66, r"""        RoadSearchBudget.check();
        // Do not turn a dry detour into a new sea crossing just to round grid corners.
        // Existing river/bridge runs remain allowed. This is a 4-block preview check,
        // not building-time support/width validation.
        if (terrainAware && longestWaterRun(suffixNodes) > longestWaterRun(rawNodes) + 8)
            suffixNodes = rawNodes;
"""),
(75, 75, r"""        if (mergedNodes.size() > MAX_OUTPUT_NODES)
            return RoadPlannerAutoCompleteResult.failure("道路节点过多，请分段规划");
"""),
(81, 82, r"""                "自动补全完成: " + normalized.nodes().size() + " 节点" + planningNote.get(),
"""),
(83, 83, r"""    }

    private double longestWaterRun(List<BlockPos> path) {
        double run = 0, longest = 0;
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i-1), b = path.get(i);
            double d = Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
            int n = Math.max(1, (int)Math.ceil(d/4));
            for (int k = 1; k <= n; k++) {
                RoadSearchBudget.check();
                int x = (int)Math.round(a.getX() + (b.getX()-(double)a.getX())*k/n);
                int z = (int)Math.round(a.getZ() + (b.getZ()-(double)a.getZ())*k/n);
                run = landProbe.isLand(x,z) ? 0 : run + d/n;
                longest = Math.max(longest, run);
            }
        }
        return longest;
"""),
(89, 89, r"""        } catch (RoadSearchBudget.DeadlineExceeded | CancellationException stopped) {
            throw stopped;
"""),
(135, 135, r"""            RoadSearchBudget.check();
"""),
(145, 146, r"""        int steps = Math.min(MAX_OUTPUT_NODES / 2, Math.max(1, (int) Math.ceil(distance / spacing)));
"""),
(148, 148, r"""            RoadSearchBudget.check();
""")
])
]
