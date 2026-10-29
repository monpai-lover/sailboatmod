package com.monpai.sailboatmod.roadplanner.service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntFunction;
import java.util.concurrent.atomic.AtomicInteger;

/** Outstanding shared IO stays charged after the requesting view detaches; completion owns the release. */
public final class SharedMapReadBudget implements AutoCloseable {
    private final int limit;
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile boolean closed;
    public SharedMapReadBudget(int limit) { this.limit = Math.max(1, limit); }
    public <T> List<CompletableFuture<T>> request(int count, IntFunction<CompletableFuture<T>> start) {
        if (count < 1 || count > limit || closed) return null;
        int current;
        do {
            current = inFlight.get();
            if (current > limit - count || closed) return null;
        } while (!inFlight.compareAndSet(current, current + count));
        if (closed) { inFlight.addAndGet(-count); return null; }
        List<CompletableFuture<T>> futures = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CompletableFuture<T> future;
            try { future = java.util.Objects.requireNonNull(start.apply(i)); }
            catch (RuntimeException | Error failure) {
                inFlight.addAndGet(-(count - i));
                throw failure;
            }
            futures.add(future);
            future.whenComplete((result, failure) -> inFlight.decrementAndGet());
        }
        return List.copyOf(futures);
    }
    public int inFlight() { return inFlight.get(); }
    @Override public void close() { closed = true; }
}
