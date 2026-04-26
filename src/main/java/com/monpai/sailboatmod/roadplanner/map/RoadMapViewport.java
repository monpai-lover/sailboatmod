package com.monpai.sailboatmod.roadplanner.map;

public record RoadMapViewport(double left, double top, double width, double height) {
    public RoadMapViewport {
        if (width <= 0.0D) {
            throw new IllegalArgumentException("width must be positive");
        }
        if (height <= 0.0D) {
            throw new IllegalArgumentException("height must be positive");
        }
    }
}
