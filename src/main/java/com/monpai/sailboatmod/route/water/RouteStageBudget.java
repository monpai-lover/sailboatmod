package com.monpai.sailboatmod.route.water;

import java.util.function.LongSupplier;

/** Per-stage CPU and idle-wait budgets, with the original search+wait wall-clock ceiling.
 * Healthy async frontiers must not time out simply because many short IO waits add up.
 */
final class RouteStageBudget {
    private final LongSupplier clock;
    private final long searchLimit, waitLimit, started, wallLimit;
    private long last, searchNanos, waitNanos, lastCompleted;
    private boolean waiting;

    RouteStageBudget(long searchMillis, long waitMillis) {
        this(searchMillis, waitMillis, System::nanoTime);
    }

    RouteStageBudget(long searchMillis, long waitMillis, LongSupplier clock) {
        this.clock = clock;
        searchLimit = Math.max(1, searchMillis) * 1_000_000L;
        waitLimit = Math.max(1, waitMillis) * 1_000_000L;
        wallLimit = searchLimit + waitLimit;
        last = started = clock.getAsLong();
    }

    /** Only newly completed expansions count. Polling the same missing edge, cache
     * hits, request submission and failed retries do NOT renew the idle allowance.
     * CPU time and the hard wall-clock deadline are never reset by progress.
     */
    void completedExpansions(long completed) {
        if (completed > lastCompleted) {
            lastCompleted = completed;
            waitNanos = 0;
        }
    }

    WaterRouteFailureReason advance(boolean nextWaiting) {
        long now = clock.getAsLong();
        long delta = Math.max(0, now - last);
        if (waiting) waitNanos += delta; else searchNanos += delta;
        last = now;
        waiting = nextWaiting;
        if (waitNanos >= waitLimit) return WaterRouteFailureReason.TERRAIN_NOT_READY;
        if (searchNanos >= searchLimit || Math.max(0, now - started) >= wallLimit)
            return WaterRouteFailureReason.TIMEOUT;
        return WaterRouteFailureReason.NONE;
    }
}
