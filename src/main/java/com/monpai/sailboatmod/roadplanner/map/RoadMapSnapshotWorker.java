package com.monpai.sailboatmod.roadplanner.map;

import java.util.List;
import java.util.Objects;

public class RoadMapSnapshotWorker {
    private final RoadMapColorizer colorizer;

    public RoadMapSnapshotWorker(RoadMapColorizer colorizer) {
        this.colorizer = Objects.requireNonNull(colorizer, "colorizer");
    }

    public RoadMapSnapshot build(long createdAtGameTime, RoadMapRegion region, List<RoadMapColumnSample> samples) {
        int expectedPixels = region.pixelWidth() * region.pixelHeight();
        if (samples.size() != expectedPixels) {
            throw new IllegalArgumentException("sample count must match region pixel count");
        }

        int[] pixels = new int[expectedPixels];
        for (int index = 0; index < samples.size(); index++) {
            pixels[index] = colorizer.color(samples.get(index));
        }
        return new RoadMapSnapshot(createdAtGameTime, region, samples, pixels);
    }
}
