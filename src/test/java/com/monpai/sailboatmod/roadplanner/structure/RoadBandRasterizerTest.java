package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBandRasterizerTest {
    @Test
    void turnFootprintFillsInsideCornerSweptArea() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 64, 0, 4),
                point(4, 64, 4, 8)
        );

        List<List<BlockPos>> byIndex = RoadBandRasterizer.surfacePositionsByIndex(centerline, 5);
        Set<BlockPos> all = flatten(byIndex);

        assertTrue(all.contains(new BlockPos(3, 64, 1)), all.toString());
        assertTrue(all.contains(new BlockPos(4, 64, 2)), all.toString());
        assertFalse(byIndex.stream().anyMatch(List::isEmpty), byIndex.toString());
    }

    @Test
    void lateralMovementFootprintContainsSweptBlocksBetweenCenters() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 64, 2, 4),
                point(8, 64, 2, 8)
        );

        Set<BlockPos> all = flatten(RoadBandRasterizer.surfacePositionsByIndex(centerline, 5));

        assertTrue(all.contains(new BlockPos(2, 64, 1)), all.toString());
        assertTrue(all.contains(new BlockPos(3, 64, 2)), all.toString());
        assertTrue(all.contains(new BlockPos(5, 64, 2)), all.toString());
    }

    @Test
    void rasterizedAdjacentBucketsTouchOrOverlap() {
        List<RoadCenterlinePoint> centerline = List.of(
                point(0, 64, 0, 0),
                point(4, 65, 0, 4),
                point(4, 66, 4, 8),
                point(8, 66, 4, 12)
        );

        List<List<BlockPos>> byIndex = RoadBandRasterizer.surfacePositionsByIndex(centerline, 5);

        for (int i = 1; i < byIndex.size(); i++) {
            assertTrue(touchesOrOverlaps(byIndex.get(i - 1), byIndex.get(i)),
                    "bucket " + (i - 1) + " -> " + i + " disconnected: " + byIndex);
        }
    }

    private static RoadCenterlinePoint point(int x, int y, int z, double dist) {
        return new RoadCenterlinePoint(new BlockPos(x, y, z), 0, RoadPlannerSegmentType.ROAD, y, y, dist);
    }

    private static Set<BlockPos> flatten(List<List<BlockPos>> byIndex) {
        Set<BlockPos> out = new HashSet<>();
        for (List<BlockPos> positions : byIndex) {
            out.addAll(positions);
        }
        return out;
    }

    private static boolean touchesOrOverlaps(List<BlockPos> first, List<BlockPos> second) {
        for (BlockPos left : first) {
            for (BlockPos right : second) {
                int dx = Math.abs(left.getX() - right.getX());
                int dy = Math.abs(left.getY() - right.getY());
                int dz = Math.abs(left.getZ() - right.getZ());
                if (dx + dy + dz <= 1) {
                    return true;
                }
            }
        }
        return false;
    }
}
