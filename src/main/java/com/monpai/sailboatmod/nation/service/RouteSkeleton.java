package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;

import java.util.List;

public record RouteSkeleton(List<Segment> segments) {
    public RouteSkeleton {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public enum SegmentType {
        GROUND,
        SLOPE
    }

    public record Segment(SegmentType type, BlockPos start, BlockPos end) {
    }
}
