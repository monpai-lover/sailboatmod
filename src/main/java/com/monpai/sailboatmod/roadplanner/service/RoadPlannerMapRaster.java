package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.roadplanner.map.RoadMapColorizer;
import com.monpai.sailboatmod.roadplanner.map.RoadMapColumnSampler;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapSnapshot;
import java.util.List;
import java.util.function.LongSupplier;

/** Incremental game-thread capture. Retains colors only, not 65,536 column objects or waiting futures. */
final class RoadPlannerMapRaster {
    private final RoadMapRegion region;
    private final int[] pixels;
    private final RoadMapColorizer colorizer = new RoadMapColorizer();
    private int cursor;

    RoadPlannerMapRaster(RoadMapRegion region) {
        this.region = region;
        long count = (long) region.pixelWidth() * region.pixelHeight();
        if (count < 1 || count > 256 * 256) throw new IllegalArgumentException("Oversized preload raster");
        pixels = new int[(int) count];
    }

    int advance(RoadMapColumnSampler sampler, int budget, long deadline, LongSupplier clock) {
        int processed = 0, width = region.pixelWidth(), stride = region.lod().blocksPerPixel();
        while (cursor < pixels.length && processed < budget && clock.getAsLong() < deadline) {
            int x = region.minX() + (cursor % width) * stride;
            int z = region.minZ() + (cursor / width) * stride;
            pixels[cursor++] = colorizer.color(sampler.sample(x, z));
            processed++;
        }
        return processed;
    }
    boolean complete() { return cursor == pixels.length; }
    RoadMapSnapshot snapshot(long gameTime) {
        if (!complete()) throw new IllegalStateException("Snapshot not complete");
        return new RoadMapSnapshot(gameTime, region, List.of(), pixels);
    }
}
