package com.monpai.sailboatmod.client.ui.state;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Tracks requests sent to the server that have not been answered yet.
 *
 * <p>Screens use it to show a pending state, ignore a second click on the same action while the first is in flight,
 * and report a timeout when no reply arrives. It only governs presentation: the server still validates every packet.
 * Time is measured in client ticks so the class stays deterministic in tests.</p>
 */
public final class PendingRequests {
    /** Default wait before a request is reported as unanswered: 5 seconds at 20 ticks per second. */
    public static final int DEFAULT_TIMEOUT_TICKS = 100;

    private final int timeoutTicks;
    private final Map<String, Long> startedAt = new LinkedHashMap<>();

    public PendingRequests() {
        this(DEFAULT_TIMEOUT_TICKS);
    }

    public PendingRequests(int timeoutTicks) {
        this.timeoutTicks = Math.max(1, timeoutTicks);
    }

    /**
     * Marks {@code key} as in flight.
     *
     * @return {@code false} when the same key is already waiting for a reply, so the caller must not send again.
     */
    public boolean begin(String key, long nowTick) {
        if (key == null || key.isBlank() || startedAt.containsKey(key)) {
            return false;
        }
        startedAt.put(key, nowTick);
        return true;
    }

    public boolean isPending(String key) {
        return key != null && startedAt.containsKey(key);
    }

    public boolean isAnyPending() {
        return !startedAt.isEmpty();
    }

    /** Clears one request, typically when a reply or notice for it arrives. */
    public void resolve(String key) {
        if (key != null) {
            startedAt.remove(key);
        }
    }

    /** Clears every request; used when fresh authoritative data replaces the whole view. */
    public void resolveAll() {
        startedAt.clear();
    }

    /**
     * Removes and returns the oldest request that has waited longer than the timeout, if any. Call once per tick and
     * surface the returned key as a "no reply" message.
     */
    public Optional<String> pollTimedOut(long nowTick) {
        for (Map.Entry<String, Long> entry : startedAt.entrySet()) {
            if (nowTick - entry.getValue() >= timeoutTicks) {
                String key = entry.getKey();
                startedAt.remove(key);
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }
}
