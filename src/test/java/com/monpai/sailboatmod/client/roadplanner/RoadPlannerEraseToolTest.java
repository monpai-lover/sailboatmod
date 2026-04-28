package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPlannerEraseToolTest {
    @Test
    void eraserDoesNotDeleteProtectedStartNode() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(10, 64, 0), RoadPlannerSegmentType.ROAD);

        boolean erased = new RoadPlannerEraseTool().eraseNode(plan, 0, true);

        assertEquals(false, erased);
        assertEquals(2, plan.nodeCount());
    }

    @Test
    void eraserDeletesNonStartNodeAndRepairsSegments() {
        RoadPlannerLinePlan plan = new RoadPlannerLinePlan();
        plan.addClickNode(new BlockPos(0, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(10, 64, 0), RoadPlannerSegmentType.ROAD);
        plan.addClickNode(new BlockPos(20, 64, 0), RoadPlannerSegmentType.BRIDGE_MAJOR);

        boolean erased = new RoadPlannerEraseTool().eraseNode(plan, 1, true);

        assertEquals(true, erased);
        assertEquals(2, plan.nodeCount());
        assertEquals(1, plan.segmentCount());
    }
}
