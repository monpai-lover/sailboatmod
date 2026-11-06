package com.monpai.sailboatmod.market.web.map;

import java.util.function.LongSupplier;

/** One soft deadline and shared capture/probe quotas for an actual server tick. */
final class MarketWebMapTickBudget {
    private final LongSupplier nanoClock;
    private boolean initialized;
    private long tickId;
    private long startedAt;
    private long limitNanos;
    private int captureLimit;
    private int probeLimit;
    private int captures;
    private int probeStarts;

    MarketWebMapTickBudget(LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
    }

    boolean beginTick(long id, long nanos, int maxCaptures, int maxProbeStarts) {
        if (initialized && tickId == id) {
            return false;
        }
        initialized = true;
        tickId = id;
        startedAt = nanoClock.getAsLong();
        limitNanos = Math.max(0L, nanos);
        captureLimit = Math.max(0, maxCaptures);
        probeLimit = Math.max(0, maxProbeStarts);
        captures = 0;
        probeStarts = 0;
        return true;
    }

    boolean hasTime() {
        return initialized && nanoClock.getAsLong() - startedAt < limitNanos;
    }

    boolean tryCapture() {
        if (!hasTime() || captures >= captureLimit) {
            return false;
        }
        captures++;
        return true;
    }

    boolean tryStartProbe(int inflight, int maxInflight) {
        if (!hasTime() || probeStarts >= probeLimit || inflight >= maxInflight) {
            return false;
        }
        probeStarts++;
        return true;
    }

    int captures() { return captures; }
    int probeStarts() { return probeStarts; }
    long elapsedNanos() { return initialized ? Math.max(0L, nanoClock.getAsLong() - startedAt) : 0L; }
}
