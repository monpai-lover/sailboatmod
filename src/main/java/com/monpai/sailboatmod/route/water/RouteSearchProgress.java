package com.monpai.sailboatmod.route.water;

import java.util.HashSet;
import java.util.Set;

/** Request-thread-only, geometry-based progress. Samples and pending/failed edges are NOT progress.
 * Feed only expanded nodes connected to an endpoint through checked edges. Retain this object
 * across grid-size retries so revisiting the same coastline cannot award time twice.
 */
final class RouteSearchProgress {
    static final int APPROACH_QUANTUM_BLOCKS = 16;
    static final long APPROACH_REWARD_MILLIS = 1_000;
    static final int REGION_BLOCKS = 128;
    static final int DETOUR_REGIONS_PER_REWARD = 8;
    static final long DETOUR_REWARD_MILLIS = 5_000;
    static final long MAX_DETOUR_REWARD_MILLIS = 60_000;
    private static final int MAX_REWARDED_REGIONS = (int) (MAX_DETOUR_REWARD_MILLIS
            / DETOUR_REWARD_MILLIS * DETOUR_REGIONS_PER_REWARD);

    record Snapshot(double bestForwardRemaining, double bestBackwardRemaining,
                    double approachBlocks, long approachMilestones, int newRegions,
                    long approachRewardMillis, long detourRewardMillis) {
        long rewardMillis() { return approachRewardMillis + detourRewardMillis; }
    }

    private final int startX, startZ, goalX, goalZ;
    private final double straightDistance;
    private double bestForward, bestBackward;
    private final Set<Long> regions = new HashSet<>();
    private int newRegions;

    RouteSearchProgress(int startX, int startZ, int goalX, int goalZ) {
        this.startX = startX; this.startZ = startZ;
        this.goalX = goalX; this.goalZ = goalZ;
        straightDistance = Math.hypot((double) goalX - startX, (double) goalZ - startZ);
        bestForward = bestBackward = straightDistance;
        // The two roots do not earn exploration credit.
        regions.add(regionKey(startX, startZ));
        regions.add(regionKey(goalX, goalZ));
    }

    void expanded(boolean forward, int x, int z) {
        double remaining = forward ? Math.hypot((double) goalX - x, (double) goalZ - z)
                : Math.hypot((double) startX - x, (double) startZ - z);
        if (forward) bestForward = Math.min(bestForward, remaining);
        else bestBackward = Math.min(bestBackward, remaining);
        // An exploration grant is finite and tracks distinct spatial regions, not dense nodes.
        if (newRegions < MAX_REWARDED_REGIONS && regions.add(regionKey(x, z))) newRegions++;
    }

    Snapshot snapshot() {
        // Sum best advances of the two rooted fronts, capped by the original separation.
        // This is a budget heuristic, NOT a measured frontier gap or a proof of connectivity.
        double advance = Math.min(straightDistance, Math.max(0, straightDistance - bestForward)
                + Math.max(0, straightDistance - bestBackward));
        long milestones = (long) Math.floor(advance / APPROACH_QUANTUM_BLOCKS);
        long detour = Math.min(MAX_DETOUR_REWARD_MILLIS,
                (newRegions / DETOUR_REGIONS_PER_REWARD) * DETOUR_REWARD_MILLIS);
        return new Snapshot(bestForward, bestBackward, advance, milestones, newRegions,
                milestones * APPROACH_REWARD_MILLIS, detour);
    }

    private static long regionKey(int x, int z) {
        return ((long) Math.floorDiv(x, REGION_BLOCKS) << 32)
                | (Math.floorDiv(z, REGION_BLOCKS) & 0xffffffffL);
    }
}
