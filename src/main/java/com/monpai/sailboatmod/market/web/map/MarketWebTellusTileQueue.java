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

    /** @return 是否新排入(已在排队/执行中、或已关闭 → false)。 */
    public boolean request(int zoom, int tileX, int tileY, Runnable job) {
        synchronized (this) {
            Key key = new Key(zoom, tileX, tileY);
            if (shutdown) {
                return false;
            }
            if (!known.add(key)) {
                moveToFront(key); // 玩家回到看过的区域:还在排队的瓦片挪回最前面
                return false;
            }
            pending.addLast(new AbstractMap.SimpleImmutableEntry<>(key, job));
            while (pending.size() > capacity) {
                known.remove(pending.pollFirst().getKey());
            }
            // One worker owns the bounded deque until it is empty. Scheduling one runnable per request would
            // otherwise move the unbounded backlog into the executor, even after old tile jobs are dropped here.
            if (!drainScheduled) {
                drainScheduled = true;
                worker.execute(this::drain);
            }
        }
        return true;
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
    }

    private void drain() {
        while (true) {
            Map.Entry<Key, Runnable> next;
            synchronized (this) {
                if (shutdown || pending.isEmpty()) {
                    drainScheduled = false;
                    return;
                }
                next = pending.pollLast();
            }
            try {
                next.getValue().run();
            } catch (Throwable t) {
                LOGGER.warn("[WebMap] Tellus tile {} failed: {}", next.getKey(), t.toString());
            } finally {
                synchronized (this) {
                    known.remove(next.getKey());
                }
            }
        }
    }
}
