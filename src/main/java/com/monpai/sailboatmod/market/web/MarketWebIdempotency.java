package com.monpai.sailboatmod.market.web;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Makes write requests that carry an {@code Idempotency-Key} header run at most once.
 *
 * <p>The web page sends one key per purchase intent (the same key is reused if the player retries after a
 * network error). The first request with a key runs the action and its response is remembered for a few
 * minutes; a later request with the same key and scope gets that stored response instead of buying again,
 * and a concurrent duplicate gets 409 while the first one is still running. Server errors (5xx) are not
 * stored, so a genuine retry can still go through. Requests without the header behave exactly as before.
 */
public final class MarketWebIdempotency {
    public record StoredResponse(int status, String contentType, byte[] body) {
    }

    static final long DEFAULT_TTL_MILLIS = 10 * 60 * 1000L;
    static final int MAX_KEY_LENGTH = 128;
    static final int MAX_SLOTS = 10_000;

    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;
    private final StoredResponse inFlightResponse;
    private volatile long lastPrune;

    public MarketWebIdempotency() {
        this(DEFAULT_TTL_MILLIS, System::currentTimeMillis, "这笔请求正在处理中，请勿重复提交。");
    }

    MarketWebIdempotency(long ttlMillis, LongSupplier clock, String inFlightMessage) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
        String json = "{\"success\":false,\"error\":\"duplicate_request\",\"message\":\"" + escapeJson(inFlightMessage) + "\"}";
        this.inFlightResponse = new StoredResponse(409, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Runs {@code action} once per (scope, key). Scope should identify the player and the endpoint, for example
     * {@code playerUuid + " " + requestPath}, so two players can never collide on a key.
     */
    public StoredResponse execute(String scope, String key, Supplier<StoredResponse> action) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            return action.get();
        }
        long now = clock.getAsLong();
        prune(now);
        String slotKey = scope + '\n' + key.trim();
        Slot fresh = new Slot(now);
        Slot existing = slots.putIfAbsent(slotKey, fresh);
        if (existing != null) {
            StoredResponse stored = existing.response;
            return stored != null ? stored : inFlightResponse;
        }
        boolean completed = false;
        try {
            StoredResponse response = action.get();
            if (response.status() >= 500) {
                slots.remove(slotKey, fresh);
            } else {
                fresh.response = response;
                fresh.touchedAt = clock.getAsLong();
            }
            completed = true;
            return response;
        } finally {
            if (!completed) {
                slots.remove(slotKey, fresh);
            }
        }
    }

    int size() {
        return slots.size();
    }

    /** Drops expired entries, at most once per second. */
    private void prune(long now) {
        if (now - lastPrune < 1000 && slots.size() < MAX_SLOTS) {
            return;
        }
        lastPrune = now;
        slots.values().removeIf(slot -> now - slot.touchedAt > ttlMillis);
        if (slots.size() > MAX_SLOTS) {
            slots.clear();
        }
    }

    private static String escapeJson(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static final class Slot {
        volatile StoredResponse response;
        volatile long touchedAt;

        Slot(long now) {
            this.touchedAt = now;
        }
    }
}
