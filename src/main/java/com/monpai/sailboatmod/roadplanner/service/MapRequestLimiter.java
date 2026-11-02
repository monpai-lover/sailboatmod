package com.monpai.sailboatmod.roadplanner.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Network-thread admission only: bounded player state and no world access or queued work for rejected traffic. */
public final class MapRequestLimiter {
    public static final MapRequestLimiter TERRAIN = new MapRequestLimiter(16, 40, 2048);
    public static final MapRequestLimiter PRELOAD = new MapRequestLimiter(4, 2, 2048);
    private static final UUID GLOBAL_KEY = new UUID(0L, 0L);
    private static final MapRequestLimiter GLOBAL = new MapRequestLimiter(64, 256, 1);
    public static boolean terrain(UUID player, long now) { return TERRAIN.allow(player, now) && GLOBAL.allow(GLOBAL_KEY, now); }
    public static boolean preload(UUID player, long now) { return PRELOAD.allow(player, now) && GLOBAL.allow(GLOBAL_KEY, now); }
    private static final long IDLE_NANOS = 60_000_000_000L;
    private final int burst;
    private final double perNano;
    private final int maxPlayers;
    private final Map<UUID, Window> players = new LinkedHashMap<>();
    private long lastCleanup;
    private boolean initialized;

    public MapRequestLimiter(int burst, int perSecond, int maxPlayers) {
        if (burst < 1 || perSecond < 1 || maxPlayers < 1) throw new IllegalArgumentException("Invalid admission limits");
        this.burst = burst;
        this.perNano = perSecond / 1_000_000_000.0;
        this.maxPlayers = maxPlayers;
    }

    public synchronized boolean allow(UUID player, long now) {
        if (player == null) return false;
        if (!initialized || now - lastCleanup >= 1_000_000_000L) {
            players.values().removeIf(window -> now - window.seen >= IDLE_NANOS);
            lastCleanup = now;
            initialized = true;
        }
        Window window = players.get(player);
        if (window == null) {
            // Do not evict an active limiter: otherwise rotating identities could reset its budget.
            if (players.size() >= maxPlayers) return false;
            window = new Window(burst, now);
            players.put(player, window);
        }
        window.tokens = Math.min(burst, window.tokens + Math.max(0L, now - window.seen) * perNano);
        window.seen = now;
        if (window.tokens < 1.0) return false;
        window.tokens -= 1.0;
        return true;
    }

    public synchronized void clear() { players.clear(); initialized = false; }
    synchronized int size() { return players.size(); }

    private static final class Window {
        double tokens;
        long seen;
        Window(double tokens, long seen) { this.tokens = tokens; this.seen = seen; }
    }
}
