package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import java.util.List;

public class RoadHeightInterpolator {

    public static int interpolateHeight(BlockPos point, List<BlockPos> centerPath, int[] heights) {
        if (point == null || centerPath == null || centerPath.isEmpty()) return point == null ? 64 : point.getY();
        return getInterpolatedY(point.getX(), point.getZ(), centerPath, heights);
    }

    public static int getInterpolatedY(int x, int z, List<BlockPos> centers, int[] targetY) {
        if (centers == null || centers.isEmpty() || targetY == null || targetY.length == 0) {
            return 64;
        }
        if (centers.size() == 1 || targetY.length == 1) {
            return targetY[0];
        }
        Projection projection = nearestProjection(x, z, centers, 0, centers.size() - 2);
        return interpolateY(projection.segmentIndex(), projection.t(), targetY);
    }

    public static int[] batchInterpolate(List<BlockPos> points, List<BlockPos> centerPath, int[] heights) {
        if (points == null || points.isEmpty()) {
            return new int[0];
        }
        int[] result = new int[points.size()];
        for (int i = 0; i < points.size(); i++) {
            BlockPos point = points.get(i);
            result[i] = point == null ? 64 : getInterpolatedY(point.getX(), point.getZ(), centerPath, heights);
        }
        return result;
    }

    public static int[] batchInterpolate(List<BlockPos> positions, int segmentIndex,
                                         List<BlockPos> centers, int[] targetY) {
        if (positions == null || positions.isEmpty()) {
            return new int[0];
        }
        int[] result = new int[positions.size()];
        if (centers == null || centers.size() < 2 || targetY == null || targetY.length == 0) {
            for (int i = 0; i < positions.size(); i++) {
                BlockPos pos = positions.get(i);
                result[i] = pos == null ? 64 : pos.getY();
            }
            return result;
        }
        int searchStart = Math.max(0, segmentIndex - 20);
        int searchEnd = Math.min(centers.size() - 2, segmentIndex + 20);
        for (int i = 0; i < positions.size(); i++) {
            BlockPos pos = positions.get(i);
            if (pos == null) {
                result[i] = 64;
                continue;
            }
            Projection projection = nearestProjection(pos.getX(), pos.getZ(), centers, searchStart, searchEnd);
            result[i] = interpolateY(projection.segmentIndex(), projection.t(), targetY);
        }
        return result;
    }

    private record Projection(int segmentIndex, double t, double distanceSq) {}

    private static Projection nearestProjection(int x, int z, List<BlockPos> centers,
                                                int searchStart, int searchEnd) {
        int start = Math.max(0, searchStart);
        int end = Math.max(start, Math.min(searchEnd, centers.size() - 2));
        Projection best = new Projection(start, 0.0D, Double.MAX_VALUE);
        for (int segment = start; segment <= end; segment++) {
            BlockPos a = centers.get(segment);
            BlockPos b = centers.get(segment + 1);
            double dx = b.getX() - a.getX();
            double dz = b.getZ() - a.getZ();
            double lengthSq = dx * dx + dz * dz;
            double t = lengthSq < 1.0E-9D
                    ? 0.0D
                    : Math.max(0.0D, Math.min(1.0D,
                    ((x - a.getX()) * dx + (z - a.getZ()) * dz) / lengthSq));
            double projectedX = a.getX() + t * dx;
            double projectedZ = a.getZ() + t * dz;
            double distanceSq = (x - projectedX) * (x - projectedX) + (z - projectedZ) * (z - projectedZ);
            if (distanceSq < best.distanceSq()) {
                best = new Projection(segment, t, distanceSq);
            }
        }
        return best;
    }

    private static int interpolateY(int segmentIndex, double t, int[] targetY) {
        int y0 = targetY[Math.max(0, Math.min(segmentIndex, targetY.length - 1))];
        int y1 = targetY[Math.max(0, Math.min(segmentIndex + 1, targetY.length - 1))];
        return (int) Math.round(y0 + (y1 - y0) * t);
    }
}