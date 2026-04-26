package com.monpai.sailboatmod.roadplanner.map;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public class RoadMapSnapshotService {
    private final Executor executor;
    private final RoadMapSnapshotWorker worker;

    public RoadMapSnapshotService(Executor executor, RoadMapColorizer colorizer) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.worker = new RoadMapSnapshotWorker(Objects.requireNonNull(colorizer, "colorizer"));
    }

    public static RoadMapSnapshotService directExecutorForTest(RoadMapColorizer colorizer) {
        return new RoadMapSnapshotService(Runnable::run, colorizer);
    }

    public CompletableFuture<RoadMapSnapshot> buildSnapshotAsync(long createdAtGameTime,
                                                                 RoadMapRegion region,
                                                                 RoadMapColumnSampler sampler) {
        List<RoadMapColumnSample> samples = sampleColumnsForTest(region, sampler);
        return CompletableFuture.supplyAsync(() -> worker.build(createdAtGameTime, region, samples), executor);
    }

    public static List<RoadMapColumnSample> sampleColumnsForTest(RoadMapRegion region, RoadMapColumnSampler sampler) {
        Objects.requireNonNull(region, "region");
        Objects.requireNonNull(sampler, "sampler");

        List<RoadMapColumnSample> samples = new ArrayList<>(region.pixelWidth() * region.pixelHeight());
        int stride = region.lod().blocksPerPixel();
        for (int pixelZ = 0; pixelZ < region.pixelHeight(); pixelZ++) {
            for (int pixelX = 0; pixelX < region.pixelWidth(); pixelX++) {
                int worldX = region.minX() + pixelX * stride;
                int worldZ = region.minZ() + pixelZ * stride;
                samples.add(sampler.sample(worldX, worldZ));
            }
        }
        return List.copyOf(samples);
    }
}
