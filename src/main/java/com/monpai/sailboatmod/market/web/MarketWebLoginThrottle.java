package com.monpai.sailboatmod.market.web;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Counts failed logins per key and refuses further attempts once a key fails {@code maxFailures} times within
 * {@code windowMillis}. Each password check costs a full PBKDF2 run, so without this an attacker could both guess
 * passwords and keep the server's CPU busy.
 */
public final class MarketWebLoginThrottle {
    private static final int DEFAULT_MAX_TRACKED = 100_000;

    private final int maxFailures;
    private final long windowMillis;
    private final LongSupplier clock;
    private final int maxTracked;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile long lastPurge;

    private record Entry(int failures, long windowStart) {
    }

    public MarketWebLoginThrottle(int maxFailures, long windowMillis, LongSupplier clock) {
        this(maxFailures, windowMillis, clock, DEFAULT_MAX_TRACKED);
    }

    /** @param maxTracked keys kept before old and unlocked ones are dropped; locked keys are never dropped early */
    public MarketWebLoginThrottle(int maxFailures, long windowMillis, LongSupplier clock, int maxTracked) {
        this.maxFailures = Math.max(1, maxFailures);
        this.windowMillis = Math.max(1L, windowMillis);
        this.clock = clock;
        this.maxTracked = Math.max(1, maxTracked);
        this.lastPurge = clock.getAsLong();
    }

    /**
     * The client's address: the connection's own, or the first X-Forwarded-For entry when the connection comes
     * from a configured reverse proxy. Anyone else could put any address in that header.
     */
    static String clientAddress(String remote, String forwardedFor, Set<String> trustedProxies) {
        if (remote != null && trustedProxies != null && trustedProxies.contains(remote) && forwardedFor != null) {
            String first = forwardedFor.split(",", 2)[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        return remote == null ? "unknown" : remote;
    }

    public boolean allowed(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return true;
        }
        if (expired(entry, clock.getAsLong())) {
            entries.remove(key, entry);
            return true;
        }
        return entry.failures() < maxFailures;
    }

    public void recordFailure(String key) {
        long now = clock.getAsLong();
        if (!entries.containsKey(key) && entries.size() >= maxTracked) {
            makeRoom(now);
            if (entries.size() >= maxTracked) {
                return; // full of locked keys: new keys are not tracked until some expire
            }
        } else if (now - lastPurge > windowMillis) {
            lastPurge = now;
            entries.entrySet().removeIf(e -> expired(e.getValue(), now));
        }
        entries.compute(key, (k, entry) -> entry == null || expired(entry, now)
                ? new Entry(1, now)
                : new Entry(entry.failures() + 1, entry.windowStart()));
    }

    public void recordSuccess(String key) {
        entries.remove(key);
    }

    int trackedKeys() {
        return entries.size();
    }

    private boolean expired(Entry entry, long now) {
        return now - entry.windowStart() > windowMillis;
    }

    /** Expired keys go first, then keys that are not locked; a flood of new keys never unlocks a locked one. */
    private void makeRoom(long now) {
        lastPurge = now;
        entries.entrySet().removeIf(e -> expired(e.getValue(), now));
        if (entries.size() >= maxTracked) {
            entries.entrySet().removeIf(e -> e.getValue().failures() < maxFailures);
        }
    }
}
