package com.monpai.sailboatmod.route.water;

import java.util.function.LongSupplier;

/** Monotonic, per-stage budgets: waiting never consumes search time and never means land. */
final class RouteStageBudget {
    private final LongSupplier clock;
    private final long searchLimit, waitLimit;
    private long last, searchNanos, waitNanos;
    private boolean waiting;

    RouteStageBudget(long searchMillis, long waitMillis) {
        this(searchMillis, waitMillis, System::nanoTime);
    }

    RouteStageBudget(long searchMillis, long waitMillis, LongSupplier clock) {
        this.clock = clock;
        searchLimit = Math.max(1, searchMillis) * 1_000_000L;
        waitLimit = Math.max(1, waitMillis) * 1_000_000L;
        last = clock.getAsLong();
    }

    WaterRouteFailureReason advance(boolean nextWaiting) {
        long now = clock.getAsLong();
        long delta = Math.max(0, now - last);
        if (waiting) waitNanos += delta; else searchNanos += delta;
        last = now;
        waiting = nextWaiting;
        if (waitNanos >= waitLimit) return WaterRouteFailureReason.TERRAIN_NOT_READY;
        if (searchNanos >= searchLimit) return WaterRouteFailureReason.TIMEOUT;
        return WaterRouteFailureReason.NONE;
    }
}
