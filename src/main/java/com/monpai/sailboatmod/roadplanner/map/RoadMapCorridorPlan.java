package com.monpai.sailboatmod.roadplanner.map;

import java.util.List;

public record RoadMapCorridorPlan(List<RoadMapCorridorRegion> regions) {
    public RoadMapCorridorPlan {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }
}
