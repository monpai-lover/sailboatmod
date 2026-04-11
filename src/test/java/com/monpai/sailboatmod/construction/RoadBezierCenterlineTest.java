package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

        BlockPos rawStart = routeNodes.get(0);
        BlockPos rawEnd = routeNodes.get(routeNodes.size() - 1);
        BlockPos resolvedStart = new BlockPos(rawStart.getX(), 66, rawStart.getZ());
        BlockPos resolvedEnd = new BlockPos(rawEnd.getX(), 63, rawEnd.getZ());
        List<BlockPos> centerline = RoadBezierCenterline.build(routeNodes, endpointAnchoredSampler(resolvedStart, resolvedEnd), Set.of());

        assertFalse(centerline.isEmpty());
        assertNotEquals(rawStart, resolvedStart, "resolved start must differ from raw route endpoint");
        assertNotEquals(rawEnd, resolvedEnd, "resolved end must differ from raw route endpoint");
        assertEquals(resolvedStart, centerline.get(0), centerline.toString());
        assertEquals(resolvedEnd, centerline.get(centerline.size() - 1), centerline.toString());
        assertTrue(isContiguous(centerline), centerline.toString());

        List<Integer> turnXs = zIncreaseXs(centerline);
        assertEquals(6, turnXs.size(), centerline.toString());
        assertTrue(turnXs.get(0) < 11, centerline.toString());
        assertTrue(turnXs.get(turnXs.size() - 1) > 16, centerline.toString());
    }

    @Test
    void avoidsBacktrackingOnTightAdjacentCorners() {
        List<BlockPos> routeNodes = new ArrayList<>();
        for (int x = 0; x <= 5; x++) {
            routeNodes.add(new BlockPos(x, 64, 0));
        }
        routeNodes.add(new BlockPos(6, 64, 1));
        routeNodes.add(new BlockPos(7, 64, 0));
        routeNodes.add(new BlockPos(8, 64, 1));
        routeNodes.add(new BlockPos(9, 64, 0));
        for (int x = 10; x <= 14; x++) {
            routeNodes.add(new BlockPos(x, 64, 0));
        }

        List<BlockPos> centerline = RoadBezierCenterline.build(routeNodes, flatSampler(), Set.of());

        assertFalse(centerline.isEmpty());
        assertTrue(isContiguous(centerline), centerline.toString());
        assertTrue(isNonDecreasingX(centerline), centerline.toString());
        assertTrue(hasNoImmediateDirectionReversal(centerline), centerline.toString());
        assertTrue(hasUniqueColumns(centerline), centerline.toString());
    }

    @Test
    void keepsSteepRiverbankApproachConnectedWhenRiseExceedsFiveBlocks() {
        List<BlockPos> routeNodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 71, 0),
                new BlockPos(4, 71, 0),
                new BlockPos(5, 71, 0)
        );

        List<BlockPos> centerline = RoadBezierCenterline.build(
                routeNodes,
                pos -> new RoadBezierCenterline.SurfaceSample(
                        switch (pos.getX()) {
                            case 0 -> new BlockPos(0, 64, 0);
                            case 1 -> new BlockPos(1, 64, 0);
                            case 2 -> new BlockPos(2, 64, 0);
                            case 3 -> new BlockPos(3, 71, 0);
                            case 4 -> new BlockPos(4, 71, 0);
                            case 5 -> new BlockPos(5, 71, 0);
                            default -> null;
                        },
                        false,
                        false,
                        0
                ),
                Set.of()
        );

        assertFalse(centerline.isEmpty());
        assertEquals(routeNodes, centerline);
    }

    private static Function<BlockPos, RoadBezierCenterline.SurfaceSample> flatSampler() {
        return pos -> new RoadBezierCenterline.SurfaceSample(
                new BlockPos(pos.getX(), 64, pos.getZ()),
                false,
                false,
                0
        );
    }

    private static Function<BlockPos, RoadBezierCenterline.SurfaceSample> endpointAnchoredSampler(BlockPos resolvedStart,
                                                                                                  BlockPos resolvedEnd) {
        return pos -> {
            long key = columnKey(pos.getX(), pos.getZ());
            BlockPos surface;
            if (key == columnKey(resolvedStart.getX(), resolvedStart.getZ())) {
                surface = resolvedStart;
            } else if (key == columnKey(resolvedEnd.getX(), resolvedEnd.getZ())) {
                surface = resolvedEnd;
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

    private static boolean isNonDecreasingX(List<BlockPos> path) {
        for (int i = 1; i < path.size(); i++) {
            if (path.get(i).getX() < path.get(i - 1).getX()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNoImmediateDirectionReversal(List<BlockPos> path) {
        for (int i = 2; i < path.size(); i++) {
            int previousDx = path.get(i - 1).getX() - path.get(i - 2).getX();
            int previousDz = path.get(i - 1).getZ() - path.get(i - 2).getZ();
            int currentDx = path.get(i).getX() - path.get(i - 1).getX();
            int currentDz = path.get(i).getZ() - path.get(i - 1).getZ();
            if (currentDx == -previousDx && currentDz == -previousDz) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasUniqueColumns(List<BlockPos> path) {
        Set<Long> seen = new java.util.HashSet<>();
        for (BlockPos pos : path) {
            if (!seen.add(columnKey(pos.getX(), pos.getZ()))) {
                return false;
            }
        }
        return true;
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }
}
