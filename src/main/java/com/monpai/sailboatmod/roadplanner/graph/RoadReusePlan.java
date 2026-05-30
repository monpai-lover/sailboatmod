package com.monpai.sailboatmod.roadplanner.graph;

import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Objects;

public record RoadReusePlan(List<BlockPos> logicalCenterline,
                            List<BlockPos> displayPath,
                            List<RoadGraphSegmentPlacement> plannedPlacements,
                            List<Range> ownedRanges,
                            List<RoadGraphReuseSpan> reuseSpans,
                            List<Rejection> rejections) {
    public RoadReusePlan {
        logicalCenterline = positions(logicalCenterline);
        displayPath = displayPath == null || displayPath.isEmpty() ? logicalCenterline : positions(displayPath);
        plannedPlacements = plannedPlacements == null ? List.of() : plannedPlacements.stream()
                .filter(Objects::nonNull)
                .toList();
        ownedRanges = ownedRanges == null ? List.of() : ownedRanges.stream()
                .filter(Objects::nonNull)
                .toList();
        reuseSpans = reuseSpans == null ? List.of() : reuseSpans.stream()
                .filter(Objects::nonNull)
                .filter(RoadGraphReuseSpan::present)
                .toList();
        rejections = rejections == null ? List.of() : rejections.stream()
                .filter(Objects::nonNull)
                .toList();
    }

    public static RoadReusePlan noReuse(List<RoadGraphSegmentPlacement> placements, List<BlockPos> centerline) {
        int last = placements == null ? -1 : placements.size() - 1;
        return new RoadReusePlan(centerline, centerline, placements,
                last >= 1 ? List.of(new Range(0, last)) : List.of(),
                List.of(), List.of());
    }

    private static List<BlockPos> positions(List<BlockPos> input) {
        return input == null ? List.of() : input.stream()
                .filter(Objects::nonNull)
                .map(BlockPos::immutable)
                .toList();
    }

    public record Range(int fromIndex, int toIndex) {
        public Range {
            fromIndex = Math.max(0, fromIndex);
            toIndex = Math.max(fromIndex, toIndex);
        }
    }

    public record Rejection(String reason, BlockPos pos) {
        public Rejection {
            reason = reason == null || reason.isBlank() ? "unknown" : reason.trim();
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
        }
    }
}
