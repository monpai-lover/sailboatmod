package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.roadplanner.weaver.highway.WeaverHighwayHeightSmoother;
import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

public final class RoadHeightProfileSmoother {
    private static final int SLOPE_RUN_BLOCKS = 3;
    private static final int SLOPE_RISE_BLOCKS = 1;
    private static final int SHALLOW_DEPRESSION_MAX_DEPTH = 10;
    private static final int SHALLOW_DEPRESSION_SUPPORT_RADIUS_BLOCKS = 8;

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
        int[] gradeY = fillShallowRoadDepressions(baseY, centers, bridgeMask);
        int[] targetY = WeaverHighwayHeightSmoother.smooth(gradeY, centers, bridgeMask, SLOPE_RUN_BLOCKS, SLOPE_RISE_BLOCKS);
        List<RoadCenterlinePoint> result = new ArrayList<>(centerline.size());
        for (int index = 0; index < centerline.size(); index++) {
            result.add(centerline.get(index).withTargetY(targetY[index]));
        }
        return List.copyOf(result);
    }

    private static int[] fillShallowRoadDepressions(int[] baseY, List<BlockPos> centers, boolean[] bridgeMask) {
        int[] filled = baseY.clone();
        for (int index = 0; index < baseY.length; index++) {
            if (bridgeMask[index]) {
                continue;
            }
            int leftSupport = highestNearbyRoadGrade(baseY, centers, bridgeMask, index, -1);
            int rightSupport = highestNearbyRoadGrade(baseY, centers, bridgeMask, index, 1);
            if (leftSupport == Integer.MIN_VALUE || rightSupport == Integer.MIN_VALUE) {
                continue;
            }
            int supportedGrade = Math.min(leftSupport, rightSupport);
            int fillDepth = supportedGrade - baseY[index];
            if (fillDepth > 0 && fillDepth <= SHALLOW_DEPRESSION_MAX_DEPTH) {
                filled[index] = supportedGrade;
            }
        }
        return filled;
    }

    private static int highestNearbyRoadGrade(int[] baseY,
                                              List<BlockPos> centers,
                                              boolean[] bridgeMask,
                                              int index,
                                              int direction) {
        int highest = Integer.MIN_VALUE;
        BlockPos origin = centers.get(index);
        for (int cursor = index + direction; cursor >= 0 && cursor < baseY.length; cursor += direction) {
            if (bridgeMask[cursor]) {
                break;
            }
            if (horizontalDistance(origin, centers.get(cursor)) > SHALLOW_DEPRESSION_SUPPORT_RADIUS_BLOCKS) {
                break;
            }
            highest = Math.max(highest, baseY[cursor]);
        }
        return highest;
    }

    private static double horizontalDistance(BlockPos a, BlockPos b) {
        double dx = b.getX() - a.getX();
        double dz = b.getZ() - a.getZ();
        return Math.sqrt((dx * dx) + (dz * dz));
    }
}
