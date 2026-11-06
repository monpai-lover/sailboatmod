package com.monpai.sailboatmod.market.web.map;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.AbstractMap;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Tellus 预测层渲染队列:专用单线程、同一瓦片去重、后进先出(当前视野先画)、超过容量丢最旧的未执行请求。
 * 与水路寻路的工作线程分开,地图请求再多也不拖慢建线。
 */
public final class MarketWebTellusTileQueue {
    public static final int CAPACITY = 256;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static MarketWebTellusTileQueue global;
    private static ExecutorService globalExecutor;

    private record Key(int zoom, int tileX, int tileY) {
    }

    private final int capacity;
    private final Executor worker;
    private final Deque<Map.Entry<Key, Runnable>> pending = new ArrayDeque<>();
    private final Set<Key> known = new HashSet<>(); // 排队中或执行中
    private boolean shutdown;
    private boolean drainScheduled;
    private Map.Entry<Key, Runnable> suspendedJob;

    public MarketWebTellusTileQueue(int capacity, Executor worker) {
        this.capacity = Math.max(1, capacity);
        this.worker = worker;
    }

    public static synchronized MarketWebTellusTileQueue global() {
        if (global == null) {
            globalExecutor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MarketWeb-TellusTiles");
                thread.setDaemon(true);
                thread.setPriority(Thread.MIN_PRIORITY);
                return thread;
            });
            global = new MarketWebTellusTileQueue(CAPACITY, globalExecutor);
        }
        return global;
    }

    /** 服务器停止时调用;下次 {@link #global()} 会建新的队列。 */
    public static synchronized void shutdownGlobal() {
        if (global != null) {
            global.shutdown();
            globalExecutor.shutdownNow();
            global = null;
            globalExecutor = null;
        }
    }

    /** Wake retained work without creating a queue when the addon has not started one. */
    public static void resumeGlobal() {
        MarketWebTellusTileQueue queue;
        synchronized (MarketWebTellusTileQueue.class) { queue = global; }
        if (queue != null) queue.resumePending();
    }

    /** @return 是否新排入(已在排队/执行中、或已关闭 → false)。 */
    public boolean request(int zoom, int tileX, int tileY, Runnable job) {
        boolean accepted;
        synchronized (this) {
            Key key = new Key(zoom, tileX, tileY);
            if (shutdown || !MarketWebMapSettings.updatesEnabled()) {
                return false;
            }
            if (!known.add(key)) {
                moveToFront(key); // 玩家回到看过的区域:还在排队的瓦片挪回最前面
                accepted = false;
            } else {
                accepted = true;
                pending.addLast(new AbstractMap.SimpleImmutableEntry<>(key, job));
                while (pending.size() > capacity) {
                    known.remove(pending.pollFirst().getKey());
                }
            }
        }
        resumePending();
        return accepted;
    }

    public void resumePending() {
        synchronized (this) {
            if (shutdown || drainScheduled || !MarketWebMapSettings.updatesEnabled()
                    || (pending.isEmpty() && suspendedJob == null)) return;
            drainScheduled = true;
        }
        try { worker.execute(this::drain); }
        catch (RuntimeException exception) {
            synchronized (this) { drainScheduled = false; }
            LOGGER.warn("[WebMap] Tellus worker could not be scheduled: {}", exception.toString());
        }
    }

    private void moveToFront(Key key) {
        for (java.util.Iterator<Map.Entry<Key, Runnable>> it = pending.descendingIterator(); it.hasNext(); ) {
            Map.Entry<Key, Runnable> entry = it.next();
            if (entry.getKey().equals(key)) {
                it.remove();
                pending.addLast(entry);
                return;
            }
        }
    }

    public synchronized void shutdown() {
        shutdown = true;
        pending.clear();
        known.clear();
        suspendedJob = null;
    }

    private void drain() {
        while (true) {
            Map.Entry<Key, Runnable> next;
            synchronized (this) {
                if (shutdown || !MarketWebMapSettings.updatesEnabled()
                        || (pending.isEmpty() && suspendedJob == null)) {
                    drainScheduled = false;
                    return;
                }
                next = suspendedJob == null ? pending.pollLast() : suspendedJob;
                suspendedJob = null;
            }
            boolean suspended = false;
            try {
                if (!MarketWebMapSettings.updatesEnabled()) throw new MarketWebMapWorkSuspendedException();
                next.getValue().run();
            } catch (MarketWebMapWorkSuspendedException pause) {
                suspended = true;
                synchronized (this) {
                    if (!shutdown) suspendedJob = next;
                    drainScheduled = false;
                }
            } catch (Throwable t) {
                LOGGER.warn("[WebMap] Tellus tile {} failed: {}", next.getKey(), t.toString());
            } finally {
                synchronized (this) {
                    if (!suspended) known.remove(next.getKey());
                }
            }
            if (suspended) {
                // A resume may race with suspension while the old drain was still marked scheduled.
                resumePending();
                return;
            }
        }
    }
}
