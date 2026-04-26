package com.monpai.sailboatmod.roadplanner.weaver.terrain;

import net.minecraft.core.BlockPos;

import java.util.List;

public final class WeaverRoadHeightInterpolator {
    private WeaverRoadHeightInterpolator() {
    }

    public static int getInterpolatedY(int x, int z, List<BlockPos> centers, int[] targetY) {
        if (centers == null || centers.isEmpty() || targetY == null || targetY.length == 0) {
            return 64;
        }
        if (centers.size() == 1 || targetY.length == 1) {
            return targetY[0];
        }
        if (targetY.length != centers.size()) {
            return targetY[0];
        }

        ProjectionResult projection = findNearestProjection(x, z, centers);
        int y0 = targetY[projection.segmentIndex()];
        int y1 = targetY[projection.segmentIndex() + 1];
        return (int) Math.round(y0 + projection.t() * (y1 - y0));
    }

    private static ProjectionResult findNearestProjection(int x, int z, List<BlockPos> centers) {
        int bestSegment = 0;
        double bestT = 0.0D;
        double bestDistSq = Double.MAX_VALUE;

        for (int index = 0; index < centers.size() - 1; index++) {
            BlockPos a = centers.get(index);
            BlockPos b = centers.get(index + 1);
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double lenSq = dx * dx + dz * dz;
            double t = lenSq < 1.0E-9D ? 0.0D : ((x - a.getX()) * dx + (z - a.getZ()) * dz) / lenSq;
            t = Math.max(0.0D, Math.min(1.0D, t));
            double projX = a.getX() + t * dx;
            double projZ = a.getZ() + t * dz;
            double distSq = (x - projX) * (x - projX) + (z - projZ) * (z - projZ);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestSegment = index;
                bestT = t;
            }
        }

        return new ProjectionResult(bestSegment, bestT);
    }

    private record ProjectionResult(int segmentIndex, double t) {
    }
}
