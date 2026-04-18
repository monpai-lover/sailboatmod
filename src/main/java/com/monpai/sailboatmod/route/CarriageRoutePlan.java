package com.monpai.sailboatmod.route;

import net.minecraft.core.BlockPos;

import java.util.List;

public record CarriageRoutePlan(List<Segment> segments) {
    public CarriageRoutePlan {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }

    public static CarriageRoutePlan empty() {
        return new CarriageRoutePlan(List.of());
    }

    public boolean found() {
        return !segments.isEmpty() && segments.stream().allMatch(segment -> segment != null && segment.path().size() >= 2);
    }

    public Segment segmentForWaypointIndex(int waypointIndex) {
        if (!found() || waypointIndex < 0) {
            return null;
        }
        int cursor = 0;
        for (Segment segment : segments) {
            int end = cursor + segment.path().size() - 1;
            if (waypointIndex <= end) {
                return segment;
            }
            cursor = end;
        }
        return segments.get(segments.size() - 1);
    }

    public record Segment(SegmentKind kind, List<BlockPos> path) {
        public Segment {
            kind = kind == null ? SegmentKind.TERRAIN_CONNECTOR : kind;
            path = path == null ? List.of() : List.copyOf(path);
        }
    }

    public enum SegmentKind {
        TERRAIN_CONNECTOR,
        ROAD_CORRIDOR
    }
}
