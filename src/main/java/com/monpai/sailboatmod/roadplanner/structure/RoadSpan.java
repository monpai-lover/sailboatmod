package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;

public record RoadSpan(RoadSpanType type,
                       int startIndex,
                       int endIndex,
                       RoadPlannerSegmentType sourceSegmentType) {
    public RoadSpan {
        type = type == null ? RoadSpanType.ROAD : type;
        startIndex = Math.max(0, startIndex);
        endIndex = Math.max(startIndex, endIndex);
        sourceSegmentType = sourceSegmentType == null ? RoadPlannerSegmentType.ROAD : sourceSegmentType;
    }

    public boolean contains(int index) {
        return index >= startIndex && index <= endIndex;
    }
}
