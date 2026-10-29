package com.monpai.sailboatmod.roadplanner.service;

import com.mojang.logging.LogUtils;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;

/** Bounded LIFO work stays here, rather than accumulating an unbounded executor backlog. */
public final class SharedMapPredictionQueue {
    private final int capacity;
    private final Executor worker;
    private final ArrayDeque<Map.Entry<String, Runnable>> pending = new ArrayDeque<>();
    private final Map<String, Map.Entry<String, Runnable>> known = new HashMap<>();
    private boolean scheduled;
    private boolean closed;

    public SharedMapPredictionQueue(int capacity, Executor worker) {
        this.capacity = Math.max(1, capacity);
        this.worker = worker;
    }

    public synchronized boolean request(String key, Runnable task) {
        if (closed || known.containsKey(key)) return false;
        Map.Entry<String, Runnable> entry = Map.entry(key, task);
        known.put(key, entry);
        pending.addLast(entry);
        while (pending.size() > capacity) {
            Map.Entry<String, Runnable> removed = pending.removeFirst();
            known.remove(removed.getKey(), removed);
        }
        if (!scheduled) {
            scheduled = true;
            worker.execute(this::drain);
        }
        return true;
    }

    public synchronized int pendingCount() { return pending.size(); }

    /** Running work observes its owner's cooperative token; queued work can be removed immediately. */
    public synchronized void cancel(String key) {
        Map.Entry<String, Runnable> removed = known.remove(key);
        if (removed != null) pending.remove(removed);
    }

    public synchronized void shutdown() {
        closed = true;
        pending.clear();
        known.clear();
    }

    private void drain() {
        while (true) {
            Map.Entry<String, Runnable> next;
            synchronized (this) {
                if (closed || pending.isEmpty()) {
                    scheduled = false;
                    return;
                }
                next = pending.removeLast();
            }
            try {
                next.getValue().run();
            } catch (RuntimeException | LinkageError unavailable) {
                LogUtils.getLogger().warn("Shared map tile {} unavailable: {}", next.getKey(), unavailable.toString());
            } finally {
                synchronized (this) {
                    if (known.get(next.getKey()) == next) known.remove(next.getKey());
                }
            }
        }
    }
}
