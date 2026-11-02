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
    private static final long MAX_RETRY_NANOS = 8_000_000_000L;
    private long capacityRejected;

    /** Only the planner thread harvests results. A completed failed future must enter
     * cooldown BEFORE an eviction decision; isDone alone does not make it evictable. */
    private void harvest(Entry<V> entry, long now) {
        if (entry.future == null || !entry.future.isDone()) return;
        CompletableFuture<V> future = entry.future;
        V value = null;
        try { value = future.getNow(null); }
        catch (CompletionException | CancellationException unavailable) { /* unknown, never land */ }
        active.remove(future);
        entry.future = null;
        if (value != null && usable.test(value)) {
            entry.ready = value; entry.failures = 0; completed++;
        } else {
            entry.failures = Math.min(31, entry.failures + 1);
            long delay = Math.min(MAX_RETRY_NANOS, RETRY_NANOS << Math.min(5, entry.failures - 1));
            entry.retryAt = now + delay;
            retries++;
        }
    }

    private boolean makeRoom(long now) {
        if (entries.size() < capacity) return true;
        // Prefer reusable successes over destroying failure history. Cooling entries
        // and in-flight work are pinned. If all are pinned, apply backpressure.
        for (var it = entries.entrySet().iterator(); it.hasNext();) {
            Entry<V> old = it.next().getValue(); harvest(old, now);
            if (old.ready != null) { it.remove(); return true; }
        }
        for (var it = entries.entrySet().iterator(); it.hasNext();) {
            Entry<V> old = it.next().getValue();
            if (old.future == null && now >= old.retryAt) { it.remove(); return true; }
        }
        capacityRejected++;
        return false;
    }

    private static final class Entry<V> {
        CompletableFuture<V> future;
        long retryAt;
        int failures;
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
            if (entry == null && !makeRoom(now)) { lastPending = key; return null; }
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
        harvest(entry, now);
        if (entry.ready == null) lastPending = key;
        return entry.ready;
    }

    int pending() {
        active.removeIf(CompletableFuture::isDone);
        return active.size();
    }
    int size() { return entries.size(); }
    String diagnostic() {
        return "submitted=" + submitted + " ready=" + completed + " inFlight=" + pending()
                + " retry=" + retries + " throttled=" + rejected + " capacityThrottled=" + capacityRejected + " last=" + lastPending;
    }
    @Override public void close() {
        closed = true;
        entries.values().forEach(entry -> { if (entry.future != null) entry.future.cancel(true); });
        entries.clear(); active.clear();
    }
}
