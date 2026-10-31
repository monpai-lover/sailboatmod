package com.monpai.sailboatmod.route.water;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/** Request-owned, bounded, nonblocking future cache. Only the planner thread accesses the map.
 * Pending work is never discarded/reissued merely because a search slice ended. A failed read is
 * retryable, not evidence of dry land. The supplied starter MUST schedule work, not perform IO inline.
 */
final class DeferredTerrainCache<K, V> implements AutoCloseable {
    private final Function<K, CompletableFuture<V>> start;
    private final Predicate<V> usable;
    private final int capacity, maxPending;
    private final LongSupplier clock;
    private final Map<K, Entry<V>> entries = new LinkedHashMap<>(128, 0.75F, true);
    private boolean closed;
    private final java.util.Set<CompletableFuture<V>> active = new java.util.HashSet<>();
    private long submitted, completed, retries, rejected;
    private K lastPending;
    private static final long RETRY_NANOS = 250_000_000L;

    private static final class Entry<V> {
        CompletableFuture<V> future;
        long retryAt;
        V ready;
        Entry(CompletableFuture<V> future) { this.future = future; }
    }
    DeferredTerrainCache(Function<K, CompletableFuture<V>> start, Predicate<V> usable, int capacity, int maxPending) {
        this(start, usable, capacity, maxPending, System::nanoTime);
    }
    DeferredTerrainCache(Function<K, CompletableFuture<V>> start, Predicate<V> usable,
                         int capacity, int maxPending, LongSupplier clock) {
        this.start = start; this.usable = usable; this.capacity = Math.max(1, capacity);
        this.maxPending = Math.max(1, Math.min(capacity, maxPending)); this.clock = clock;
    }
    V poll(K key) {
        if (closed) return null;
        Entry<V> entry = entries.get(key);
        if (entry != null && entry.ready != null) return entry.ready;
        long now = clock.getAsLong();
        if (entry != null && entry.future == null && now < entry.retryAt) { lastPending = key; return null; }
        if (entry == null || entry.future == null) {
            if (pending() >= maxPending) { lastPending = key; rejected++; return null; }
            if (entry == null && entries.size() >= capacity) {
                var iterator = entries.entrySet().iterator();
                while (iterator.hasNext()) {
                    Entry<V> old = iterator.next().getValue();
                    if (old.future == null || old.future.isDone()) { iterator.remove(); break; }
                }
                if (entries.size() >= capacity) { lastPending = key; return null; }
            }
            CompletableFuture<V> future;
            try { future = start.apply(key); }
            catch (RuntimeException error) { future = CompletableFuture.failedFuture(error); }
            if (future == null) future = CompletableFuture.completedFuture(null);
            if (entry == null) { entry = new Entry<>(future); entries.put(key, entry); }
            else entry.future = future;
            active.add(future);
            submitted++;
        }
        if (!entry.future.isDone()) { lastPending = key; return null; }
        V value = null;
        try { value = entry.future.getNow(null); }
        catch (CompletionException | CancellationException unavailable) { /* retried, never converted to land */ }
        active.remove(entry.future);
        entry.future = null;
        if (value != null && usable.test(value)) { entry.ready = value; completed++; return value; }
        entry.retryAt = now + RETRY_NANOS;
        retries++; lastPending = key;
        return null;
    }
    int pending() {
        active.removeIf(CompletableFuture::isDone);
        return active.size();
    }
    int size() { return entries.size(); }
    String diagnostic() {
        return "submitted=" + submitted + " ready=" + completed + " inFlight=" + pending()
                + " retry=" + retries + " throttled=" + rejected + " last=" + lastPending;
    }
    @Override public void close() {
        closed = true;
        entries.values().forEach(entry -> { if (entry.future != null) entry.future.cancel(true); });
        entries.clear(); active.clear();
    }
}
