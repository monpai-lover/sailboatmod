package com.monpai.sailboatmod.roadplanner.map;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record RoadMapSnapshot(long createdAtGameTime,
                              RoadMapRegion region,
                              List<RoadMapColumnSample> columns,
                              int[] argbPixels) {
    public RoadMapSnapshot {
        region = Objects.requireNonNull(region, "region");
        columns = columns == null ? List.of() : List.copyOf(columns);
        argbPixels = argbPixels == null ? new int[0] : Arrays.copyOf(argbPixels, argbPixels.length);
    }

    @Override
    public int[] argbPixels() {
        return Arrays.copyOf(argbPixels, argbPixels.length);
    }
}
