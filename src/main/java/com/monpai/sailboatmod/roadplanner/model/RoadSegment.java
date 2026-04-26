package com.monpai.sailboatmod.roadplanner.model;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

public record RoadSegment(int regionIndex,
                          BlockPos regionCenter,
                          BlockPos entryPoint,
                          BlockPos exitPoint,
                          List<RoadStroke> strokes,
                          boolean completed) {
    public RoadSegment {
        if (regionIndex < 0) {
            throw new IllegalArgumentException("regionIndex must be non-negative");
        }
        regionCenter = Objects.requireNonNull(regionCenter, "regionCenter").immutable();
        entryPoint = entryPoint == null ? null : entryPoint.immutable();
        exitPoint = exitPoint == null ? null : exitPoint.immutable();
        strokes = strokes == null ? List.of() : List.copyOf(strokes);
    }
}
