package com.monpai.sailboatmod.route.water.debug;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Server-only bounded work/results; HTTP requests never own a route computation's lifetime. */
final class RouteDebugJobManager implements AutoCloseable {
    enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    @FunctionalInterface interface Work { String run(BooleanSupplier cancelled) throws Exception; }
    record Snapshot(String jobId, Status status, long elapsedMillis, String result, String message) { }
    static final class BusyException extends RuntimeException {
        BusyException() { super("调试队列已满，请等待当前任务完成或取消任务"); }
    }

    private static final int MAX_RECORDS = 4;
    private final LongSupplier clock;
    private final long timeoutMillis;
    private final long retentionMillis;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker;
    private final ScheduledExecutorService maintenance;
    private boolean closed;

    RouteDebugJobManager() { this(() -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()), 600_000, 600_000); }

    RouteDebugJobManager(LongSupplier clock, long timeoutMillis, long retentionMillis) {
        this.clock = clock;
        this.timeoutMillis = timeoutMillis;
        this.retentionMillis = retentionMillis;
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
            Thread thread = new Thread(task, "RouteDebug-Job");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        maintenance = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "RouteDebug-JobExpiry");
            thread.setDaemon(true);
            return thread;
        });
        maintenance.scheduleAtFixedRate(this::expire, 1, 1, TimeUnit.SECONDS);
    }

    synchronized Snapshot submit(Work work) {
        if (closed) throw new IllegalStateException("Debug server stopped");
        expire();
        if (jobs.size() >= MAX_RECORDS) {
            Iterator<Job> records = jobs.values().iterator();
            while (records.hasNext()) {
                if (terminal(records.next().status)) { records.remove(); break; }
            }
        }
        if (jobs.size() >= MAX_RECORDS) throw new BusyException();
        Job job = new Job(UUID.randomUUID().toString(), clock.getAsLong());
        job.future = new FutureTask<>(() -> { execute(job, work); return null; });
        jobs.put(job.id, job);
        try { worker.execute(job.future); }
        catch (RejectedExecutionException full) { jobs.remove(job.id); throw new BusyException(); }
        return snapshot(job);
    }

    synchronized Snapshot get(String id) {
        expire();
        Job job = jobs.get(id);
        return job == null ? null : snapshot(job);
    }

    synchronized Snapshot cancel(String id) {
        expire();
        Job job = jobs.get(id);
        if (job == null) return null;
        if (!terminal(job.status)) finish(job, Status.CANCELLED, null, "任务已取消");
        return snapshot(job);
    }

    private void execute(Job job, Work work) {
        synchronized (this) {
            if (terminal(job.status) || closed) return;
            job.status = Status.RUNNING;
        }
        try {
            String result = work.run(() -> cancelled(job));
            synchronized (this) {
                if (!terminal(job.status)) finish(job, Status.SUCCEEDED, result, null);
            }
        } catch (Throwable failure) {
            synchronized (this) {
                if (!terminal(job.status)) finish(job,
                        failure instanceof InterruptedException || failure instanceof CancellationException ? Status.CANCELLED : Status.FAILED,
                        null, failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
            }
        }
    }

    private synchronized boolean cancelled(Job job) {
        expire();
        return closed || terminal(job.status) || Thread.currentThread().isInterrupted();
    }

    private synchronized void expire() {
        long now = clock.getAsLong();
        Iterator<Job> records = jobs.values().iterator();
        while (records.hasNext()) {
            Job job = records.next();
            if (!terminal(job.status) && now - job.created >= timeoutMillis)
                finish(job, Status.TIMED_OUT, null, "任务超过 10 分钟上限，已停止");
            if (terminal(job.status) && now - job.finished >= retentionMillis) records.remove();
        }
    }

    private void finish(Job job, Status status, String result, String message) {
        job.status = status;
        job.result = result;
        job.message = message;
        job.finished = clock.getAsLong();
        if (status == Status.CANCELLED || status == Status.TIMED_OUT) {
            job.future.cancel(true);
            worker.remove(job.future);
        }
    }

    private Snapshot snapshot(Job job) {
        long end = terminal(job.status) ? job.finished : clock.getAsLong();
        return new Snapshot(job.id, job.status, Math.max(0, end - job.created), job.result, job.message);
    }

    private static boolean terminal(Status status) { return status != Status.QUEUED && status != Status.RUNNING; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (Job job : jobs.values()) if (!terminal(job.status)) finish(job, Status.CANCELLED, null, "调试服务已停止");
        jobs.clear();
        maintenance.shutdownNow();
        worker.shutdownNow();
    }

    private static final class Job {
        final String id;
        final long created;
        long finished;
        Status status = Status.QUEUED;
        String result;
        String message;
        FutureTask<Void> future;
        Job(String id, long created) { this.id = id; this.created = created; }
    }
}
