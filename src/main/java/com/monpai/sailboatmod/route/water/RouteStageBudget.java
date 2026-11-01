package com.monpai.sailboatmod.route.water;

import java.util.function.LongSupplier;

/** Per-stage search/wait accounting. Legacy callers retain fixed limits; hierarchical planning
 * explicitly opts into bounded geometric-progress grants. A timed-out stage cannot resurrect.
 */
final class RouteStageBudget {
    static final long MAX_ADAPTIVE_STAGE_MILLIS = 480_000L;
    private final LongSupplier clock;
    private final long searchLimit, waitLimit, started, baseWallLimit, hardWallLimit;
    private final boolean adaptive;
    private long last, searchNanos, waitNanos, lastCompleted, lastUseful;
    private long rewardNanos, bestApproachMilestone, bestDetourRewardMillis;
    private boolean waiting;
    private WaterRouteFailureReason terminal = WaterRouteFailureReason.NONE;
    private String stopCause = "none";

    RouteStageBudget(long searchMillis, long waitMillis) {
        this(searchMillis, waitMillis, System::nanoTime);
    }
    RouteStageBudget(long searchMillis, long waitMillis, LongSupplier clock) {
        this(searchMillis, waitMillis, clock, false);
    }
    private RouteStageBudget(long searchMillis, long waitMillis, LongSupplier clock, boolean adaptive) {
        this.clock = clock; this.adaptive = adaptive;
        searchLimit = nanos(searchMillis); waitLimit = nanos(waitMillis);
        baseWallLimit = saturatedAdd(searchLimit, waitLimit);
        // Never reduce a caller's base allowance. Standard 90s+60s can earn at most 480s.
        hardWallLimit = adaptive ? Math.max(baseWallLimit, Math.min(nanos(MAX_ADAPTIVE_STAGE_MILLIS),
                saturatedAdd(baseWallLimit, saturatedAdd(baseWallLimit, saturatedAdd(baseWallLimit, baseWallLimit)))))
                : baseWallLimit;
        lastUseful = last = started = clock.getAsLong();
    }
    static RouteStageBudget adaptive(long searchMillis, long waitMillis, LongSupplier clock) {
        return new RouteStageBudget(searchMillis, waitMillis, clock, true);
    }

    /** Node count remains useful for diagnostics/legacy wait accounting, but is not adaptive credit. */
    void completedExpansions(long completed) {
        if (completed > lastCompleted) {
            lastCompleted = completed;
            if (!adaptive) waitNanos = 0;
        }
    }

    /** Called after advance(false), at the instant a genuinely connected node is expanded.
     * Never accept credit after an existing deadline, and do not reset deadlines on retries.
     */
    void reward(RouteSearchProgress.Snapshot progress) {
        if (!adaptive || terminal != WaterRouteFailureReason.NONE || progress == null) return;
        if (advance(waiting) != WaterRouteFailureReason.NONE) return;
        boolean useful = progress.approachMilestones() > bestApproachMilestone
                || progress.detourRewardMillis() > bestDetourRewardMillis;
        bestApproachMilestone = Math.max(bestApproachMilestone, progress.approachMilestones());
        bestDetourRewardMillis = Math.max(bestDetourRewardMillis, progress.detourRewardMillis());
        rewardNanos = Math.max(rewardNanos,
                Math.min(hardWallLimit - baseWallLimit, nanosAllowZero(progress.rewardMillis())));
        if (useful) lastUseful = clock.getAsLong();
    }

    WaterRouteFailureReason advance(boolean nextWaiting) {
        if (terminal != WaterRouteFailureReason.NONE) return terminal;
        long now = clock.getAsLong();
        long delta = Math.max(0, now - last);
        if (waiting) waitNanos = saturatedAdd(waitNanos, delta);
        else searchNanos = saturatedAdd(searchNanos, delta);
        last = now;
        boolean wasWaiting = waiting;
        waiting = nextWaiting;
        long elapsed = Math.max(0, now - started);
        if (elapsed >= hardWallLimit) return stop(WaterRouteFailureReason.TIMEOUT, "hard_stage_limit");
        if (elapsed >= wallLimitNanos()) return stop(WaterRouteFailureReason.TIMEOUT, "earned_budget_exhausted");
        if (searchNanos >= searchLimitNanos()) return stop(WaterRouteFailureReason.TIMEOUT, "search_budget_exhausted");
        if (adaptive && Math.max(0, now - lastUseful) >= waitLimit)
            return stop(wasWaiting ? WaterRouteFailureReason.TERRAIN_NOT_READY : WaterRouteFailureReason.TIMEOUT,
                    "no_geometric_progress");
        if (!adaptive && waitNanos >= waitLimit)
            return stop(WaterRouteFailureReason.TERRAIN_NOT_READY, "terrain_wait_limit");
        return terminal;
    }

    long awardedMillis() { return rewardNanos / 1_000_000L; }
    long elapsedMillis() { return Math.max(0, clock.getAsLong() - started) / 1_000_000L; }
    long wallLimitMillis() { return wallLimitNanos() / 1_000_000L; }
    long remainingMillis() { return Math.max(0, wallLimitMillis() - elapsedMillis()); }
    long searchLimitMillis() { return searchLimitNanos() / 1_000_000L; }
    String diagnostic() {
        return "elapsedMs=" + elapsedMillis() + " wallLimitMs=" + wallLimitMillis()
                + " grantedMs=" + awardedMillis() + " remainingMs=" + remainingMillis()
                + " searchMs=" + searchNanos / 1_000_000L + "/" + searchLimitMillis()
                + " idleMs=" + Math.max(0, clock.getAsLong() - lastUseful) / 1_000_000L
                + " stop=" + stopCause;
    }

    private long wallLimitNanos() { return saturatedAdd(baseWallLimit, rewardNanos); }
    private long searchLimitNanos() {
        // Data latency may earn wall time without permitting unlimited CPU expenditure.
        return saturatedAdd(searchLimit, Math.min(searchLimit, rewardNanos / 2));
    }
    private WaterRouteFailureReason stop(WaterRouteFailureReason reason, String cause) {
        terminal = reason; stopCause = cause; return reason;
    }
    private static long nanos(long millis) { return nanosAllowZero(Math.max(1, millis)); }
    private static long nanosAllowZero(long millis) {
        return Math.min(Long.MAX_VALUE / 1_000_000L, Math.max(0, millis)) * 1_000_000L;
    }
    private static long saturatedAdd(long a, long b) { return a > Long.MAX_VALUE - b ? Long.MAX_VALUE : a + b; }
}
