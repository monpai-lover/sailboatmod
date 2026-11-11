package com.monpai.sailboatmod.roadplanner.corridor;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadAlignmentTest {
    private static double[] at(RoadAlignment alignment, double u) {
        double[] out = new double[4];
        alignment.evaluate(u, out);
        return out;
    }

    private static List<RoadPlannerSegmentType> roads(int legs) {
        List<RoadPlannerSegmentType> types = new ArrayList<>();
        for (int i = 0; i < legs; i++) {
            types.add(RoadPlannerSegmentType.ROAD);
        }
        return types;
    }

    @Test
    void aStraightRoadIsLeftExactlyWhereItWasDrawn() {
        RoadAlignment alignment = RoadAlignment.of(List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 40), new BlockPos(60, 64, 80)), roads(2));
        for (double u = 0; u <= 100; u += 2.5) {
            double[] p = at(alignment, u);
            assertEquals(u * 0.6, p[0], 1.0E-9);
            assertEquals(u * 0.8, p[1], 1.0E-9);
            assertEquals(0.6, p[2], 1.0E-9);
            assertEquals(0.8, p[3], 1.0E-9);
        }
    }

    @Test
    void theRoadStartsAndEndsOnItsFirstAndLastNode() {
        RoadAlignment alignment = RoadAlignment.of(List.of(new BlockPos(5, 64, 5), new BlockPos(25, 64, 5), new BlockPos(25, 64, 30)), roads(2));
        assertArrayEquals(new double[]{5, 5}, new double[]{at(alignment, 0)[0], at(alignment, 0)[1]}, 1.0E-9);
        assertArrayEquals(new double[]{25, 30}, new double[]{at(alignment, 45)[0], at(alignment, 45)[1]}, 1.0E-9);
    }

    @Test
    void aRightAngleBecomesACurveThatTurnsSteadilyAndStaysCloseToTheCorner() {
        RoadAlignment alignment = RoadAlignment.of(List.of(new BlockPos(0, 64, 0), new BlockPos(100, 64, 0), new BlockPos(100, 64, 100)), roads(2));
        double previousHeading = 0;
        double largestTurn = 0;
        for (double u = 60; u <= 140; u += 1) {
            double[] p = at(alignment, u);
            double heading = Math.atan2(p[3], p[2]);
            assertTrue(heading >= previousHeading - 1.0E-9, "the curve must never turn back on itself");
            largestTurn = Math.max(largestTurn, heading - previousHeading);
            previousHeading = heading;
        }
        assertEquals(Math.PI / 2, previousHeading, 1.0E-9);
        assertTrue(Math.toDegrees(largestTurn) < 6, "no kink: the sharpest turn in one block was " + Math.toDegrees(largestTurn));
        double[] apex = at(alignment, 100);
        assertTrue(Math.hypot(apex[0] - 100, apex[1]) < 6, "the curve should pass within a few blocks of the drawn corner");
        assertArrayEquals(new double[]{80, 0}, new double[]{at(alignment, 80)[0], at(alignment, 80)[1]}, 1.0E-9,
                "beyond the smoothing radius the legs are untouched");
    }

    @Test
    void closelySpacedNodesOnACircleBlendIntoOneCurveOfThatRadius() {
        List<BlockPos> nodes = new ArrayList<>();
        for (int i = 0; i <= 20; i++) {
            double angle = i * 0.15;
            nodes.add(new BlockPos((int) Math.round(80 * Math.sin(angle)), 64, (int) Math.round(80 - 80 * Math.cos(angle))));
        }
        RoadAlignment alignment = RoadAlignment.of(nodes, roads(20));
        double previous = Double.NaN;
        for (double u = 20; u <= alignment.endDistance() - 20; u += 1) {
            double[] p = at(alignment, u);
            assertEquals(80, Math.hypot(p[0], p[1] - 80), 1.5, "the curve should follow the circle the nodes lie on");
            double heading = Math.atan2(p[3], p[2]);
            if (!Double.isNaN(previous)) {
                assertTrue(Math.abs(Math.toDegrees(heading - previous) - Math.toDegrees(1 / 80.0)) < 1.2,
                        "heading should change evenly, not in steps at each node");
            }
            previous = heading;
        }
    }

    @Test
    void nodesReinterpolatedEveryFewBlocksAndRoundedGiveTheSameCurveAsTheOriginalLegs() {
        List<BlockPos> sparse = List.of(new BlockPos(0, 64, 0), new BlockPos(70, 64, 31), new BlockPos(96, 64, 103), new BlockPos(180, 64, 120));
        List<BlockPos> dense = new ArrayList<>();
        for (int leg = 0; leg < sparse.size() - 1; leg++) {
            BlockPos a = sparse.get(leg);
            BlockPos b = sparse.get(leg + 1);
            int pieces = (int) Math.ceil(Math.hypot(b.getX() - a.getX(), b.getZ() - a.getZ()) / 8);
            for (int i = leg == 0 ? 0 : 1; i <= pieces; i++) {
                dense.add(new BlockPos((int) Math.round(a.getX() + (b.getX() - a.getX()) * i / (double) pieces), 64,
                        (int) Math.round(a.getZ() + (b.getZ() - a.getZ()) * i / (double) pieces)));
            }
        }
        RoadAlignment original = RoadAlignment.of(sparse, roads(3));
        RoadAlignment resubmitted = RoadAlignment.of(dense, roads(dense.size() - 1));
        for (double t = 0; t <= 1.0; t += 0.01) {
            double[] p = at(original, original.endDistance() * t);
            double[] q = at(resubmitted, resubmitted.endDistance() * t);
            assertTrue(Math.hypot(p[0] - q[0], p[1] - q[1]) < 1.0,
                    "resubmitting a route through the client must not change its shape; drift at " + t);
        }
    }

    @Test
    void aWindowOfTheRouteReproducesTheWholeRouteBitForBit() {
        List<BlockPos> nodes = new ArrayList<>();
        java.util.Random random = new java.util.Random(7);
        int x = 0;
        int z = 0;
        for (int i = 0; i < 60; i++) {
            nodes.add(new BlockPos(x, 60 + random.nextInt(20), z));
            x += 3 + random.nextInt(30);
            z += random.nextInt(41) - 20;
        }
        List<RoadPlannerSegmentType> types = roads(nodes.size() - 1);
        double[] distances = new double[nodes.size()];
        for (int i = 1; i < nodes.size(); i++) {
            distances[i] = distances[i - 1] + Math.hypot((double) nodes.get(i).getX() - nodes.get(i - 1).getX(),
                    (double) nodes.get(i).getZ() - nodes.get(i - 1).getZ());
        }
        RoadAlignment whole = RoadAlignment.of(nodes, types);
        for (double from = 0; from < whole.endDistance(); from += 97) {
            double to = Math.min(whole.endDistance(), from + 150);
            RoadAlignment window = RoadAlignment.slice(nodes, types, distances, from, to);
            for (double u = Math.ceil(from); u <= to; u += 1) {
                assertArrayEquals(at(whole, u), at(window, u), 0.0, "geometry differs at " + u);
                assertEquals(whole.nodeHeightAt(u), window.nodeHeightAt(u), 0.0);
                assertEquals(whole.routeLegAt(u), window.routeLegAt(u));
            }
        }
    }
}
