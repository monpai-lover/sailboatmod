package com.monpai.sailboatmod.client.roadplanner;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadPlannerTerrainSampleIndex {
    private static final int MAX_NEAREST_SAMPLE_DISTANCE_BLOCKS = 6;
    private static final int MAX_NEAREST_HEIGHT_SAMPLE_DISTANCE_BLOCKS = 24;
    private final Map<Long, RoadPlannerTerrainSample> samplesByColumn;

    private RoadPlannerTerrainSampleIndex(Map<Long, RoadPlannerTerrainSample> samplesByColumn) {
        this.samplesByColumn = samplesByColumn == null ? Map.of() : Map.copyOf(samplesByColumn);
    }

    public static RoadPlannerTerrainSampleIndex from(List<RoadPlannerTerrainSample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new RoadPlannerTerrainSampleIndex(Map.of());
        }
        LinkedHashMap<Long, RoadPlannerTerrainSample> indexed = new LinkedHashMap<>();
        for (RoadPlannerTerrainSample sample : samples) {
            if (sample != null) {
                indexed.put(columnKey(sample.x(), sample.z()), sample);
            }
        }
        return new RoadPlannerTerrainSampleIndex(indexed);
    }

    public boolean isEmpty() {
        return samplesByColumn.isEmpty();
    }

    public boolean isLand(int x, int z, RoadPlannerBridgeRuleService.LandProbe fallback) {
        RoadPlannerTerrainSample sample = sampleAt(x, z);
        if (sample == null) {
            return fallback == null || fallback.isLand(x, z);
        }
        return sample.kind() != RoadPlannerTerrainSample.Kind.WATER || sample.waterDepth() <= 0;
    }

    public int heightAt(int x, int z, RoadPlannerHeightSampler fallback) {
        RoadPlannerTerrainSample sample = sampleAt(x, z, MAX_NEAREST_HEIGHT_SAMPLE_DISTANCE_BLOCKS);
        if (sample == null || sample.kind() == RoadPlannerTerrainSample.Kind.UNKNOWN) {
            return fallback == null ? 64 : fallback.heightAt(x, z);
        }
        return sample.surfaceY();
    }

    public int waterDepthAt(int x, int z, RoadPlannerWaterDepthProbe fallback) {
        RoadPlannerTerrainSample sample = sampleAt(x, z);
        if (sample == null) {
            return fallback == null ? 0 : fallback.waterDepthAt(x, z);
        }
        return sample.kind() == RoadPlannerTerrainSample.Kind.WATER ? sample.waterDepth() : 0;
    }

    private RoadPlannerTerrainSample sampleAt(int x, int z) {
        return sampleAt(x, z, MAX_NEAREST_SAMPLE_DISTANCE_BLOCKS);
    }

    private RoadPlannerTerrainSample sampleAt(int x, int z, int maxDistanceBlocks) {
        RoadPlannerTerrainSample exact = samplesByColumn.get(columnKey(x, z));
        if (exact != null) {
            return exact;
        }
        RoadPlannerTerrainSample best = null;
        int bestDistance = Integer.MAX_VALUE;
        int radius = Math.max(0, maxDistanceBlocks);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int distance = dx * dx + dz * dz;
                if (distance >= bestDistance) {
                    continue;
                }
                RoadPlannerTerrainSample candidate = samplesByColumn.get(columnKey(x + dx, z + dz));
                if (candidate != null) {
                    best = candidate;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
