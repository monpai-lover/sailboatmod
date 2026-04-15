package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

public final class RoadPlanningPassContext {
    private static final int EQUIVALENT_SEGMENT_XZ_TOLERANCE = 4;
    private static final int EQUIVALENT_SEGMENT_Y_TOLERANCE = 6;

    private final RoadTerrainSamplingCache terrainCache;
    private final RoadPlanningSnapshot snapshot;
    private final Set<SegmentKey> failedSegments = new LinkedHashSet<>();
    private final Map<Long, BlockPos> roadSurfaceCache = new LinkedHashMap<>();
    private RoadPlanningFailureReason preferredFailureReason = RoadPlanningFailureReason.NONE;

    public RoadPlanningPassContext(Level level) {
        this(level, null);
    }

    public RoadPlanningPassContext(Level level, RoadPlanningSnapshot snapshot) {
        this.terrainCache = new RoadTerrainSamplingCache(Objects.requireNonNull(level, "level"));
        this.snapshot = snapshot;
        if (snapshot != null) {
            this.terrainCache.seedColumns(snapshot.terrainColumns());
        }
    }

    public RoadTerrainSamplingCache.TerrainColumn sampleColumn(int x, int z) {
        return terrainCache.sample(x, z);
    }

    public boolean snapshotBacked() {
        return snapshot != null;
    }

    public RoadPlanningSnapshot snapshot() {
        return snapshot;
    }

    public boolean markFailedSegment(BlockPos from, BlockPos to) {
        return markFailedSegment(from, to, false);
    }

    public boolean hasFailedSegment(BlockPos from, BlockPos to) {
        return hasFailedEquivalentSegment(from, to, false);
    }

    public boolean markFailedSegment(BlockPos from, BlockPos to, boolean allowWaterFallback) {
        if (from == null || to == null) {
            return false;
        }
        return failedSegments.add(new SegmentKey(from.immutable(), to.immutable(), allowWaterFallback));
    }

    public boolean hasFailedEquivalentSegment(BlockPos from, BlockPos to, boolean allowWaterFallback) {
        if (from == null || to == null) {
            return false;
        }
        SegmentKey probe = new SegmentKey(from.immutable(), to.immutable(), allowWaterFallback);
        if (failedSegments.contains(probe)) {
            return true;
        }
        for (SegmentKey failed : failedSegments) {
            if (failed.allowWaterFallback() != allowWaterFallback) {
                continue;
            }
            if (equivalent(failed.from(), probe.from()) && equivalent(failed.to(), probe.to())) {
                return true;
            }
        }
        return false;
    }

    public BlockPos resolveRoadSurface(int x, int z, BiFunction<Integer, Integer, BlockPos> sampler) {
        long key = BlockPos.asLong(x, 0, z);
        if (roadSurfaceCache.containsKey(key)) {
            return roadSurfaceCache.get(key);
        }
        BlockPos surface = sampler == null ? null : sampler.apply(x, z);
        roadSurfaceCache.put(key, surface == null ? null : surface.immutable());
        return surface;
    }

    public void recordFailure(RoadPlanningFailureReason reason) {
        if (reason == null || reason == RoadPlanningFailureReason.NONE) {
            return;
        }
        if (preferredFailureReason == RoadPlanningFailureReason.SEARCH_EXHAUSTED) {
            return;
        }
        if (reason == RoadPlanningFailureReason.SEARCH_EXHAUSTED
                || preferredFailureReason == RoadPlanningFailureReason.NONE) {
            preferredFailureReason = reason;
        }
    }

    public RoadPlanningFailureReason preferredFailureReason() {
        return preferredFailureReason;
    }

    private static boolean equivalent(BlockPos left, BlockPos right) {
        return left != null
                && right != null
                && Math.abs(left.getX() - right.getX()) <= EQUIVALENT_SEGMENT_XZ_TOLERANCE
                && Math.abs(left.getZ() - right.getZ()) <= EQUIVALENT_SEGMENT_XZ_TOLERANCE
                && Math.abs(left.getY() - right.getY()) <= EQUIVALENT_SEGMENT_Y_TOLERANCE;
    }

    private record SegmentKey(BlockPos from, BlockPos to, boolean allowWaterFallback) {
    }
}
