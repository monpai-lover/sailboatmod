package com.monpai.sailboatmod.road.pathfinding.cache;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/** Completed column values only, FIFO eviction; no eviction/retry loop for pending futures.
 * Unbounded by default for legacy consumers. Autocomplete explicitly opts into a per-map cap.
 */
final class BoundedColumnCache<V> {
    private final ConcurrentHashMap<Long,V> values = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> insertionOrder = new ConcurrentLinkedQueue<>();
    private volatile int limit = Integer.MAX_VALUE;
    void limit(int maximum) {
        int previous = limit; limit = Math.max(16, maximum);
        if (previous == Integer.MAX_VALUE) insertionOrder.addAll(values.keySet());
        trim();
    }
    V computeIfAbsent(Long key, Function<Long,V> sample) {
        boolean[] inserted = {false};
        V result = values.computeIfAbsent(key, k -> {
            V value = sample.apply(k);
            inserted[0] = value != null;
            return value;
        });
        if (inserted[0] && limit < Integer.MAX_VALUE) { insertionOrder.add(key); trim(); }
        return result;
    }
    private void trim() {
        while (values.size() > limit) {
            Long oldest = insertionOrder.poll();
            if (oldest == null) return;
            values.remove(oldest);
        }
    }
    int size() { return values.size(); }
    void clear() { values.clear(); insertionOrder.clear(); }
}
