package com.monpai.sailboatmod.roadplanner.build;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;

import java.util.List;

public record RoadPlannerConfirmBuildResult(RoadGraphEdge edge,
                                            RoadBuildJob job,
                                            List<RoadBuildStep> visiblePreview) {
    public RoadPlannerConfirmBuildResult {
        if (edge == null || job == null) {
            throw new IllegalArgumentException("edge and job cannot be null");
        }
        visiblePreview = visiblePreview == null ? List.of() : List.copyOf(visiblePreview);
    }
}
