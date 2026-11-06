package com.monpai.sailboatmod.market.web.map;

import java.util.LinkedHashMap;

/** Bounded timestamps of completed captures; invalidated work always bypasses suppression. */
final class MarketWebMapRefreshCooldown {
    private final int capacity;
    private final LinkedHashMap<Long, Long> completed = new LinkedHashMap<>();

    MarketWebMapRefreshCooldown(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    synchronized boolean ready(long key, long nowMillis, long cooldownMillis, boolean dirty) {
        Long last = completed.get(key);
        return dirty || last == null || nowMillis < last || nowMillis - last >= cooldownMillis;
    }

    synchronized void record(long key, long nowMillis) {
        completed.remove(key);
        completed.put(key, nowMillis);
        while (completed.size() > capacity) {
            var iterator = completed.keySet().iterator();
            iterator.next();
            iterator.remove();
        }
    }

    synchronized void clear() { completed.clear(); }
}
