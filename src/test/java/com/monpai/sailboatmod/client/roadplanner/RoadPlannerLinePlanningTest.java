package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerLinePlanningTest {
    @Test
    void roadToolAddsDiscreteClickNodesNotBrushNodes() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();

        assertTrue(plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD).accepted());
        assertTrue(plan.addClickNode(new BlockPos(20, 65, 0), RoadPlannerSegmentType.ROAD).accepted());

        assertEquals(2, plan.nodeCount());
        assertEquals(1, plan.segmentCount());
    }

    @Test
    void unresolvedMajorBridgeBlocksConfirm() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(80, 64, 0), RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE);

        assertTrue(plan.hasUnresolvedBridgeBlocker());
        assertFalse(plan.canConfirm());
    }
}
