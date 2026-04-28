package com.monpai.sailboatmod.client.roadplanner;

public record RoadPlannerNodeSelection(int nodeIndex) {
    public RoadPlannerNodeSelection {
        if (nodeIndex < 0) {
            throw new IllegalArgumentException("nodeIndex must be >= 0");
        }
    }
}
