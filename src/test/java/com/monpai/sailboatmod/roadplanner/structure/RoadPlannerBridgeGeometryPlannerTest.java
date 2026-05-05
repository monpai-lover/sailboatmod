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
                centerline(0, 24, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
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
        assertTrue(plan.points().stream().allMatch(point -> point.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
    }

    @Test
    void longSpanPiersStartAtOceanFloorAndReachDeck() {
        RoadPlannerBridgeGeometryPlanner.Plan plan = RoadPlannerBridgeGeometryPlanner.plan(
                centerline(0, 24, 63),
                new RoadSpan(RoadSpanType.BRIDGE, 0, 24, RoadPlannerSegmentType.BRIDGE_MAJOR),
                waterSampler(63, 54),
                new BridgeConfig()
        );

        assertFalse(plan.piers().isEmpty());
        assertTrue(plan.piers().stream().allMatch(pier -> pier.bottomY() == 54));
        assertTrue(plan.piers().stream().allMatch(pier -> pier.topY() == plan.deckY()));
    }

    private static List<RoadCenterlinePoint> centerline(int startX, int endX, int y) {
        java.util.ArrayList<RoadCenterlinePoint> points = new java.util.ArrayList<>();
        for (int x = startX; x <= endX; x++) {
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
}
