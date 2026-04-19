package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import java.util.List;

public class RoadHeightInterpolator {

    public static int interpolateHeight(BlockPos point, List<BlockPos> centerPath, int[] heights) {
        if (centerPath.isEmpty()) return point.getY();
        int bestIdx = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < centerPath.size(); i++) {
            BlockPos c = centerPath.get(i);
            double dx = point.getX() - c.getX();
            double dz = point.getZ() - c.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIdx = i;
            }
        }
        if (bestIdx >= heights.length) return point.getY();

        if (bestIdx < centerPath.size() - 1) {
            BlockPos a = centerPath.get(bestIdx);
            BlockPos b = centerPath.get(bestIdx + 1);
            double segX = b.getX() - a.getX(), segZ = b.getZ() - a.getZ();
            double segLenSq = segX * segX + segZ * segZ;
            if (segLenSq > 0.001) {
                double t = ((point.getX() - a.getX()) * segX + (point.getZ() - a.getZ()) * segZ) / segLenSq;
                t = Math.max(0, Math.min(1, t));
                return (int) Math.round(heights[bestIdx] + (heights[bestIdx + 1] - heights[bestIdx]) * t);
            }
        }
        return heights[bestIdx];
    }

    public static int[] batchInterpolate(List<BlockPos> points, List<BlockPos> centerPath, int[] heights) {
        int[] result = new int[points.size()];
        int searchStart = 0;
        for (int i = 0; i < points.size(); i++) {
            BlockPos p = points.get(i);
            int bestIdx = searchStart;
            double bestDistSq = Double.MAX_VALUE;
            int searchEnd = Math.min(centerPath.size(), searchStart + 40);
            for (int j = Math.max(0, searchStart - 20); j < searchEnd; j++) {
                BlockPos c = centerPath.get(j);
                double dx = p.getX() - c.getX(), dz = p.getZ() - c.getZ();
                double distSq = dx * dx + dz * dz;
                if (distSq < bestDistSq) {
                    bestDistSq = distSq;
                    bestIdx = j;
                }
            }
            searchStart = bestIdx;
            result[i] = (bestIdx < heights.length) ? heights[bestIdx] : p.getY();
        }
        return result;
    }
}
