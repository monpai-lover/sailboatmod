package com.monpai.sailboatmod.market.web.map;

/** Fast bounded pipeline supply is independent from recoverable-state checkpoint frequency. */
final class MarketWebMapBackgroundCadence {
    static final int PIPELINE_INTERVAL_TICKS = 5;
    private boolean dirty;

    boolean admissionDue(long tick) { return tick % PIPELINE_INTERVAL_TICKS == 0; }
    void markDirty() { dirty = true; }
    boolean checkpointDue(long tick, int interval) { return dirty && tick % Math.max(1, interval) == 0; }
    void checkpointRequested() { dirty = false; }

    static boolean partialFlushDue(long tick, long firstPendingTick, Long lastFlushTick) {
        return tick - firstPendingTick >= PIPELINE_INTERVAL_TICKS
                && (lastFlushTick == null || tick - lastFlushTick >= PIPELINE_INTERVAL_TICKS);
    }
}
