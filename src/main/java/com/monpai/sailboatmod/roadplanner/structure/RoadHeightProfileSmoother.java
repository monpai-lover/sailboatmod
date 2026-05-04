package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.roadplanner.weaver.highway.WeaverHighwayHeightSmoother;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadHeightProfileSmoother {
    private static final int SLOPE_RUN_BLOCKS = 3;
    private static final int SLOPE_RISE_BLOCKS = 1;

    private RoadHeightProfileSmoother() {
    }

    private static boolean isBridgeIndex(List<RoadSpan> spans, int index) {
        if (spans == null) {
            return false;
        }
        for (RoadSpan span : spans) {
            if (span.type() == RoadSpanType.BRIDGE && span.contains(index)) {
                return true;
            }
        }
        return false;
    }

    public static List<RoadCenterlinePoint> smooth(List<RoadCenterlinePoint> centerline, List<RoadSpan> spans) {
        if (centerline == null || centerline.isEmpty()) {
            return List.of();
        }
        int[] baseY = new int[centerline.size()];
        boolean[] bridgeMask = new boolean[centerline.size()];
        List<BlockPos> centers = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            RoadCenterlinePoint point = centerline.get(index);
            baseY[index] = point.terrainY();
            centers.add(point.pos());
            bridgeMask[index] = isBridgeIndex(spans, index);
        }
        int[] targetY = WeaverHighwayHeightSmoother.smooth(baseY, centers, bridgeMask, SLOPE_RUN_BLOCKS, SLOPE_RISE_BLOCKS);
        List<RoadCenterlinePoint> result = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            result.add(centerline.get(index).withTargetY(targetY[index]));
        }
        return List.copyOf(result);
    }
}
