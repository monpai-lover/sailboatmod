package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.pathfinding.cache.TerrainSamplingCache;
import net.minecraft.core.BlockPos;
import com.monpai.sailboatmod.road.pathfinding.RoadSearchBudget;
import java.util.concurrent.CancellationException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RoadPlannerTerrainSampleCollector {
    private static final int SAMPLE_SPACING_BLOCKS = 4;
    static final int MAX_SAMPLES = 4096;

    private RoadPlannerTerrainSampleCollector() {
    }

    public static List<RoadPlannerTerrainSample> collect(TerrainSamplingCache cache, List<BlockPos> nodes) {
        if (cache == null || nodes == null || nodes.isEmpty()) {
            return List.of();
        }
        RoadSearchBudget.check();
        double total = 0;
        for (int i=1; i<nodes.size(); i++) {
            RoadSearchBudget.check();
            BlockPos a=nodes.get(i-1), b=nodes.get(i);
            if (a!=null && b!=null) total += Math.hypot(b.getX()-(double)a.getX(), b.getZ()-(double)a.getZ());
        }
        // Output is a bounded preview profile, not the authoritative build geometry.
        double spacing = Math.max(SAMPLE_SPACING_BLOCKS, total/(MAX_SAMPLES-1));
        double along=0, next=0;
        LinkedHashMap<Long, RoadPlannerTerrainSample> samples = new LinkedHashMap<>();
        for (int i=1; i<nodes.size() && samples.size()<MAX_SAMPLES-1; i++) {
            BlockPos from=nodes.get(i-1), to=nodes.get(i);
            if (from==null || to==null) continue;
            double length=Math.hypot(to.getX()-(double)from.getX(), to.getZ()-(double)from.getZ());
            while (next<=along+length && samples.size()<MAX_SAMPLES-1) {
                RoadSearchBudget.check();
                double t=length<1e-9?0:Math.clamp((next-along)/length,0,1);
                int x=(int)Math.round(from.getX()+(to.getX()-(double)from.getX())*t);
                int z=(int)Math.round(from.getZ()+(to.getZ()-(double)from.getZ())*t);
                addSample(cache,new BlockPos(x,from.getY(),z),samples);
                next+=spacing;
            }
            along+=length;
        }
        addSample(cache,nodes.getLast(),samples);
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
        RoadSearchBudget.check();
        long key=columnKey(x,z);
        if (!samples.containsKey(key)) samples.put(key, sample(cache, x, z));
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
        } catch (RoadSearchBudget.DeadlineExceeded | CancellationException stopped) {
            throw stopped;
        } catch (RuntimeException ignored) {
            return new RoadPlannerTerrainSample(x, z, 0, 0, RoadPlannerTerrainSample.Kind.UNKNOWN);
        }
    }

    private static long columnKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}
