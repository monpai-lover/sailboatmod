package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;

import java.util.ArrayList;
import java.util.List;

public final class RoadSpanClassifier {
    private RoadSpanClassifier() {
    }

    public static List<RoadSpan> classify(List<RoadCenterlinePoint> centerline) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        List<RoadSpan> spans = new ArrayList<>();
        int start = 0;
        RoadSpanType currentType = typeFor(centerline.get(0).segmentType());
        RoadPlannerSegmentType sourceType = centerline.get(0).segmentType();
        for (int index = 1; index < centerline.size(); index++) {
            RoadSpanType nextType = typeFor(centerline.get(index).segmentType());
            if (nextType != currentType) {
                spans.add(new RoadSpan(currentType, start, index - 1, sourceType));
                start = index;
                currentType = nextType;
                sourceType = centerline.get(index).segmentType();
            }
        }
        spans.add(new RoadSpan(currentType, start, centerline.size() - 1, sourceType));
        return List.copyOf(spans);
    }

    public static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL || type == RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE;
    }

    private static RoadSpanType typeFor(RoadPlannerSegmentType type) {
        if (isBridge(type)) {
            return RoadSpanType.BRIDGE;
        }
        if (type == RoadPlannerSegmentType.TUNNEL) {
            return RoadSpanType.TUNNEL;
        }
        return RoadSpanType.ROAD;
    }
}
