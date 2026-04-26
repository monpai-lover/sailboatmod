package com.monpai.sailboatmod.roadplanner.model;

public record RoadStrokeSettings(Integer widthOverride) {
    public static RoadStrokeSettings defaults() {
        return new RoadStrokeSettings(null);
    }
}
