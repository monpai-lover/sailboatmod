package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;

import java.util.Objects;

public record RoadCenterlinePoint(BlockPos pos,
                                  int segmentIndex,
                                  RoadPlannerSegmentType segmentType,
                                  int terrainY,
                                  int targetY,
                                  double distanceFromStart) {
    public RoadCenterlinePoint {
        pos = Objects.requireNonNull(pos, "pos").immutable();
        segmentType = segmentType == null ? RoadPlannerSegmentType.ROAD : segmentType;
        distanceFromStart = Math.max(0.0D, distanceFromStart);
    }

    public RoadCenterlinePoint withTargetY(int newTargetY) {
        return new RoadCenterlinePoint(pos.atY(newTargetY), segmentIndex, segmentType, terrainY, newTargetY, distanceFromStart);
    }
}
