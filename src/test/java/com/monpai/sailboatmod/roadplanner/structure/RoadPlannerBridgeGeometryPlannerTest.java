package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.config.BridgeConfig;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBridgeGeometryPlannerTest {
    @Test
    void deckHeightUsesActualWaterSurfacePlusConfigHeightWhenShoresDoNotRaiseIt() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 48, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 48, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 55),
                new BridgeConfig()
        );

        assertEquals(68, plan.deckY());
    }

    @Test
    void shortSpanDoesNotPlanPiersLikeActualBridgeBuilder() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 8, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 8, RoadPlannerSegmentType.BRIDGE_SMALL),
                waterSampler(63, 55),
                new BridgeConfig()
        );

        assertTrue(plan.piers().isEmpty());
        assertFalse(plan.points().isEmpty());
        assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
    }

    @Test
    void longSpanPiersStartAtOceanFloorAndReachDeck() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 48, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 48, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 54),
                new BridgeConfig()
        );

        assertFalse(plan.piers().isEmpty());
        assertTrue(plan.piers().stream().allMatch(pier -> pier.bottomY() == 54));
        assertTrue(plan.piers().stream().allMatch(pier -> pier.topY() == plan.deckY()));
    }

    @Test
    void shortDeepCrossingUsesLowArchWithRampsAndNoPiers() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 12, 64),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 12, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 40),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.LOW_ARCH, plan.profile());
        assertTrue(plan.piers().isEmpty());
        assertTrue(plan.deckY() <= 67, "low arch should stay close to shore height");
        assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
        assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
    }

    @Test
    void twoPointLowArchStillRaisesOnePointToDeckHeight() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 1, 64),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 1, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 40),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.LOW_ARCH, plan.profile());
        assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertTrue(plan.points().stream().anyMatch(point -> point.point().targetY() == plan.deckY()));
    }

    @Test
    void mediumCrossingUsesLowBridgeWithoutPiers() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 24, 64),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 45),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.LOW_BRIDGE, plan.profile());
        assertTrue(plan.piers().isEmpty());
        assertTrue(plan.points().stream().anyMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
    }

    @Test
    void longCrossingUsesPierBridge() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 48, 64),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 48, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 42),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
        assertFalse(plan.piers().isEmpty());
        assertTrue(plan.piers().stream().allMatch(pier -> pier.bottomY() == 42));
    }

    @Test
    void unevenShortCrossingEscalatesWhenLowArchCannotMeetRiseLimit() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 16, 64, 80),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 16, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 40),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
        assertFalse(plan.piers().isEmpty());
        assertEquals(64, plan.points().get(0).point().targetY());
        assertTrue(plan.points().get(plan.points().size() - 1).point().targetY() >= 80);
        assertAdjacentTargetYDeltaAtMostOne(plan);
    }

    @Test
    void unevenDescendingShortCrossingKeepsAdjacentHeightDeltasBuildable() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 16, 80, 64),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 16, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 40),
                new BridgeConfig()
        );

        assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, plan.profile());
        assertFalse(plan.piers().isEmpty());
        assertTrue(plan.points().get(0).point().targetY() >= 80);
        assertEquals(64, plan.points().get(plan.points().size() - 1).point().targetY());
        assertAdjacentTargetYDeltaAtMostOne(plan);
    }

    @Test
    void classifiesBridgeProfilesAtExactSpanBoundaries() {
        assertEquals(RoadPlannerBridgeProfile.LOW_ARCH, RoadPlannerBridgeProfile.classify(16));
        assertEquals(RoadPlannerBridgeProfile.LOW_BRIDGE, RoadPlannerBridgeProfile.classify(17));
        assertEquals(RoadPlannerBridgeProfile.LOW_BRIDGE, RoadPlannerBridgeProfile.classify(32));
        assertEquals(RoadPlannerBridgeProfile.PIER_BRIDGE, RoadPlannerBridgeProfile.classify(33));
    }

    private static List<RoadCenterlinePoint> centerline(int startX, int endX, int y) {
        return centerline(startX, endX, y, y);
    }

    private static List<RoadCenterlinePoint> centerline(int startX, int endX, int startY, int endY) {
        java.util.ArrayList<RoadCenterlinePoint> points = new java.util.ArrayList<>();
        int span = Math.max(1, endX - startX);
        for (int x = startX; x <= endX; x++) {
            double t = (x - startX) / (double) span;
            int y = (int) Math.round(startY + (endY - startY) * t);
            points.add(new RoadCenterlinePoint(
                    new BlockPos(x, y, 0),
                    0,
                    RoadPlannerSegmentType.BRIDGE_MAJOR,
                    y,
                    y,
                    x - startX
            ));
        }
        return List.copyOf(points);
    }

    private static RoadTerrainSampler waterSampler(int waterSurfaceY, int oceanFloorY) {
        return new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return waterSurfaceY;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return waterSurfaceY;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return oceanFloorY;
            }
        };
    }

    private static void assertAdjacentTargetYDeltaAtMostOne(RoadPlannerBridgeGeometryPlanner.Plan plan) {
        for (int index = 1; index < plan.points().size(); index++) {
            int previousY = plan.points().get(index - 1).point().targetY();
            int currentY = plan.points().get(index).point().targetY();
            assertTrue(Math.abs(currentY - previousY) <= 1,
                    "adjacent targetY delta exceeded 1 at index " + index + ": " + previousY + " -> " + currentY);
        }
    }
}
