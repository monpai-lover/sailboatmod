package com.monpai.sailboatmod.roadplanner.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class BridgeTransitionProfile {
    private static final int DEFAULT_TRANSITION_SAMPLES_PER_SIDE = 2;

    private BridgeTransitionProfile() {
    }

    static Result build(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans, RoadSpan bridgeSpan) {
        return build(centerline, spans, bridgeSpan, DEFAULT_TRANSITION_SAMPLES_PER_SIDE, DEFAULT_TRANSITION_SAMPLES_PER_SIDE);
    }

    static Result build(List<RoadCenterlinePoint> centerline,
                        List<RoadSpan> spans,
                        RoadSpan bridgeSpan,
                        int maxLeftTransitionSamples,
                        int maxRightTransitionSamples) {
        if (centerline == null || centerline.isEmpty() || bridgeSpan == null) {
            return new Result(List.of(), Set.of(), 0, 0);
        }
        int safeLeftSamples = Math.max(0, maxLeftTransitionSamples);
        int safeRightSamples = Math.max(0, maxRightTransitionSamples);
        int start = Math.max(0, Math.min(centerline.size() - 1, bridgeSpan.startIndex()));
        int end = Math.max(start, Math.min(centerline.size() - 1, bridgeSpan.endIndex()));
        List<RoadCenterlinePoint> expanded = new ArrayList<>();
        Set<Long> transitionColumns = new HashSet<>();

        int leftStart = start;
        for (int index = start - 1; index >= 0 && start - index <= safeLeftSamples; index--) {
            if (!isRoadIndex(spans, index) || duplicateOrReversed(centerline, index, leftStart)) {
                break;
            }
            leftStart = index;
        }
        for (int index = leftStart; index < start; index++) {
            RoadCenterlinePoint transition = centerline.get(index).withTargetY(centerline.get(index).targetY());
            expanded.add(transition);
            transitionColumns.add(columnKey(transition));
        }

        int originalStartOffset = expanded.size();
        for (int index = start; index <= end; index++) {
            expanded.add(centerline.get(index));
        }

        int rightEnd = end;
        for (int index = end + 1; index < centerline.size() && index - end <= safeRightSamples; index++) {
            if (!isRoadIndex(spans, index) || duplicateOrReversed(centerline, rightEnd, index)) {
                break;
            }
            rightEnd = index;
            RoadCenterlinePoint transition = centerline.get(index).withTargetY(centerline.get(index).targetY());
            expanded.add(transition);
            transitionColumns.add(columnKey(transition));
        }

        return new Result(expanded, transitionColumns, originalStartOffset, originalStartOffset + (end - start + 1));
    }

    private static boolean isRoadIndex(List<RoadSpan> spans, int index) {
        if (spans == null) {
            return false;
        }
        return spans.stream().anyMatch(span -> span.type() == RoadSpanType.ROAD && span.contains(index));
    }

    private static boolean duplicateOrReversed(List<RoadCenterlinePoint> centerline, int firstIndex, int secondIndex) {
        RoadCenterlinePoint first = centerline.get(firstIndex);
        RoadCenterlinePoint second = centerline.get(secondIndex);
        return first.pos().getX() == second.pos().getX() && first.pos().getZ() == second.pos().getZ();
    }

    static long columnKey(RoadCenterlinePoint point) {
        return columnKey(point.pos().getX(), point.pos().getZ());
    }

    static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    record Result(List<RoadCenterlinePoint> points,
                  Set<Long> transitionColumns,
                  int originalStartOffset,
                  int originalEndExclusive) {
        Result {
            points = points == null ? List.of() : List.copyOf(points);
            transitionColumns = transitionColumns == null ? Set.of() : Set.copyOf(transitionColumns);
            originalStartOffset = Math.max(0, Math.min(originalStartOffset, points.size()));
            originalEndExclusive = Math.max(originalStartOffset, Math.min(originalEndExclusive, points.size()));
        }
    }
}
