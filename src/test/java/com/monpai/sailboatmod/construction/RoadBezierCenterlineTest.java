package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBezierCenterlineTest {
    @Test
    void resamplesGentleCurveWithNearUniformTurnProgression() {
        List<BlockPos> routeNodes = new ArrayList<>();
        for (int x = 0; x <= 8; x++) {
            routeNodes.add(new BlockPos(x, 64, 0));
        }
        for (int offset = 1; offset <= 8; offset++) {
            routeNodes.add(new BlockPos(8 + offset, 64, offset));
        }
        for (int x = 17; x <= 24; x++) {
            routeNodes.add(new BlockPos(x, 64, 8));
        }

        List<BlockPos> centerline = RoadBezierCenterline.build(routeNodes, flatSampler(), Set.of());

        assertFalse(centerline.isEmpty());
        assertTrue(isContiguous(centerline), centerline.toString());

        List<Integer> turnXs = zIncreaseXs(centerline);
        assertEquals(8, turnXs.size(), centerline.toString());
        assertTrue(turnXs.get(0) <= 8, centerline.toString());
        assertTrue(turnXs.get(turnXs.size() - 1) >= 17, centerline.toString());
        assertTrue(maxGap(turnXs) <= 2, centerline.toString());
    }

    @Test
    void preservesResolvedEndpointsWhileResamplingCurveInterior() {
        List<BlockPos> routeNodes = new ArrayList<>();
        for (int x = 2; x <= 10; x++) {
            routeNodes.add(new BlockPos(x, 64, 1));
        }
        for (int offset = 1; offset <= 6; offset++) {
            routeNodes.add(new BlockPos(10 + offset, 64, 1 + offset));
        }
        for (int x = 17; x <= 22; x++) {
            routeNodes.add(new BlockPos(x, 64, 7));
        }

        BlockPos resolvedStart = new BlockPos(2, 64, 1);
        BlockPos resolvedEnd = new BlockPos(22, 64, 7);
        List<BlockPos> centerline = RoadBezierCenterline.build(routeNodes, endpointAnchoredSampler(), Set.of());

        assertFalse(centerline.isEmpty());
        assertEquals(resolvedStart, centerline.get(0), centerline.toString());
        assertEquals(resolvedEnd, centerline.get(centerline.size() - 1), centerline.toString());
        assertTrue(isContiguous(centerline), centerline.toString());

        List<Integer> turnXs = zIncreaseXs(centerline);
        assertEquals(6, turnXs.size(), centerline.toString());
        assertTrue(turnXs.get(0) < 11, centerline.toString());
        assertTrue(turnXs.get(turnXs.size() - 1) > 16, centerline.toString());
    }

    private static Function<BlockPos, RoadBezierCenterline.SurfaceSample> flatSampler() {
        return pos -> new RoadBezierCenterline.SurfaceSample(
                new BlockPos(pos.getX(), 64, pos.getZ()),
                false,
                false,
                0
        );
    }

    private static Function<BlockPos, RoadBezierCenterline.SurfaceSample> endpointAnchoredSampler() {
        return pos -> {
            long key = columnKey(pos.getX(), pos.getZ());
            BlockPos surface;
            if (key == columnKey(2, 1)) {
                surface = new BlockPos(2, 64, 1);
            } else if (key == columnKey(22, 7)) {
                surface = new BlockPos(22, 64, 7);
            } else {
                surface = new BlockPos(pos.getX(), 64, pos.getZ());
            }
            return new RoadBezierCenterline.SurfaceSample(surface, false, false, 0);
        };
    }

    private static List<Integer> zIncreaseXs(List<BlockPos> path) {
        List<Integer> xs = new ArrayList<>();
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i).getZ() > path.get(i - 1).getZ()) {
                xs.add(path.get(i).getX());
            }
        }
        return xs;
    }

    private static int maxGap(List<Integer> xs) {
        int maxGap = 0;
        for (int i = 1; i < xs.size(); i++) {
            maxGap = Math.max(maxGap, xs.get(i) - xs.get(i - 1));
        }
        return maxGap;
    }

    private static boolean isContiguous(List<BlockPos> path) {
        for (int i = 1; i < path.size(); i++) {
            int dx = Math.abs(path.get(i).getX() - path.get(i - 1).getX());
            int dz = Math.abs(path.get(i).getZ() - path.get(i - 1).getZ());
            if (Math.max(dx, dz) != 1) {
                return false;
            }
        }
        return true;
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
