package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBridgeRuleServiceTest {
    @Test
    void roadToolRejectsMajorBridgeSpan() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> true);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateRoadTool(List.of(new BlockPos(0, 64, 0)), new BlockPos(128, 64, 0));

        assertFalse(decision.accepted());
        assertEquals(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE, decision.segmentType());
    }

    @Test
    void roadToolAcceptsShortRoadSpan() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> true);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateRoadTool(List.of(new BlockPos(0, 64, 0)), new BlockPos(32, 64, 0));

        assertTrue(decision.accepted());
        assertEquals(RoadPlannerSegmentType.ROAD, decision.segmentType());
    }

    @Test
    void bridgeToolBacktracksToLastLandNode() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> x <= 16 || x >= 128);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateBridgeTool(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                new BlockPos(64, 64, 0)
        ), new BlockPos(144, 64, 0));

        assertTrue(decision.accepted());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, decision.segmentType());
        assertEquals(0, decision.bridgeStartNodeIndex());
    }

    @Test
    void bridgeToolRejectsWaterTarget() {
        RoadPlannerBridgeRuleService service = new RoadPlannerBridgeRuleService((x, z) -> x <= 16);

        RoadPlannerBridgeRuleService.Decision decision = service.evaluateBridgeTool(List.of(new BlockPos(0, 64, 0)), new BlockPos(144, 64, 0));

        assertFalse(decision.accepted());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, decision.segmentType());
        assertEquals(0, decision.bridgeStartNodeIndex());
    }

    @Test
    void linePlanCanSetSegmentTypeFromStartingNode() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(32, 64, 0), RoadPlannerSegmentType.ROAD);

        plan.setSegmentTypeFromNode(0, RoadPlannerSegmentType.BRIDGE_MAJOR);

        assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_MAJOR), plan.segments());
    }
}
