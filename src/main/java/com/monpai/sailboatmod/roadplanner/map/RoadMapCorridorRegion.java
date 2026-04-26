package com.monpai.sailboatmod.roadplanner.map;

import java.util.Objects;

public record RoadMapCorridorRegion(RoadMapRegion region, RoadMapRegionPriority priority) {
    public RoadMapCorridorRegion {
        region = Objects.requireNonNull(region, "region");
        priority = priority == null ? RoadMapRegionPriority.ROUGH_PATH : priority;
    }
}
