package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadPlannerTerrainSampleCollector {
    private static final int SAMPLE_SPACING_BLOCKS = 4;

    private RoadPlannerTerrainSampleCollector() {
    }

    public static List<RoadPlannerTerrainSample> collect(TerrainSamplingCache cache, List<BlockPos> nodes) {
        if (cache == null || nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        LinkedHashMap<Long, RoadPlannerTerrainSample> samples = new LinkedHashMap<>();
        addSample(cache, nodes.get(0), samples);
        for (int index = 1; index < nodes.size(); index++) {
            BlockPos from = nodes.get(index - 1);
            BlockPos to = nodes.get(index);
            if (from == null || to == null) {
                continue;
            }
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            int steps = Math.max(1, (int) Math.ceil(Math.max(Math.abs(dx), Math.abs(dz)) / (double) SAMPLE_SPACING_BLOCKS));
            for (int step = 0; step <= steps; step++) {
                double t = step / (double) steps;
                int x = (int) Math.round(from.getX() + dx * t);
                int z = (int) Math.round(from.getZ() + dz * t);
                addSample(cache, new BlockPos(x, from.getY(), z), samples);
            }
        }
        return List.copyOf(samples.values());
    }

    private static void addSample(TerrainSamplingCache cache,
                                  BlockPos pos,
                                  Map<Long, RoadPlannerTerrainSample> samples) {
        if (pos == null || samples == null) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        samples.put(columnKey(x, z), sample(cache, x, z));
    }

    private static RoadPlannerTerrainSample sample(TerrainSamplingCache cache, int x, int z) {
        try {
            int waterDepth = cache.getWaterDepth(x, z);
            if (cache.isWater(x, z) && waterDepth > 0) {
                return new RoadPlannerTerrainSample(
                        x,
                        z,
                        cache.getWaterSurfaceY(x, z),
                        waterDepth,
                        RoadPlannerTerrainSample.Kind.WATER);
            }
            return new RoadPlannerTerrainSample(
                    x,
                    z,
                    cache.getHeight(x, z),
                    0,
                    RoadPlannerTerrainSample.Kind.LAND);
        } catch (RuntimeException ignored) {
            return new RoadPlannerTerrainSample(x, z, 0, 0, RoadPlannerTerrainSample.Kind.UNKNOWN);
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
