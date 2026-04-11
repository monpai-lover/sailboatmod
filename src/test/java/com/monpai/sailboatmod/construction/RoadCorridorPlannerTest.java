package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.IntPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadCorridorPlannerTest {
    @Test
    void plannerClassifiesApproachBridgeheadsAndSpanTypesAndBuildsChannelMetadata() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(2, 6));
        List<RoadPlacementPlan.BridgeRange> navigableRanges = List.of(new RoadPlacementPlan.BridgeRange(3, 4));

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgeRanges, navigableRanges);

        assertTrue(plan.valid());
        assertEquals(RoadCorridorPlan.SegmentKind.LAND_APPROACH, plan.slices().get(0).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.LAND_APPROACH, plan.slices().get(1).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, plan.slices().get(2).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(3).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(4).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN, plan.slices().get(5).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, plan.slices().get(6).segmentKind());
        assertTrue(plan.slices().get(2).supportPositions().isEmpty());
        assertTrue(plan.slices().get(2).pierLightPositions().isEmpty());
        assertTrue(plan.slices().get(6).supportPositions().isEmpty());
        assertTrue(plan.slices().get(6).pierLightPositions().isEmpty());
        assertEquals(RoadCorridorPlan.SegmentKind.LAND_APPROACH, plan.slices().get(7).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.LAND_APPROACH, plan.slices().get(8).segmentKind());
        assertTrue(plan.slices().get(5).supportPositions().size() > 0);
        assertTrue(plan.slices().get(3).supportPositions().isEmpty());
        assertEquals(3, plan.navigationChannel().min().getX());
        assertEquals(4, plan.navigationChannel().max().getX());
    }

    @Test
    void plannerMarksPlanInvalidWhenProtectedMainChannelWouldNeedSupportPlacement() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(1, 7));
        List<RoadPlacementPlan.BridgeRange> navigableRanges = List.of(new RoadPlacementPlan.BridgeRange(3, 5));

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgeRanges, navigableRanges);

        assertFalse(plan.valid());
        assertTrue(plan.slices().get(4).supportPositions().isEmpty());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(4).segmentKind());
    }

    @Test
    void detectsContiguousNavigableMainChannelSubranges() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(2, 7));
        IntPredicate navigableAt = index -> index == 3 || index == 4 || index == 6;

        List<RoadPlacementPlan.BridgeRange> ranges = RoadCorridorPlanner.detectContiguousSubranges(
                centerPath,
                bridgeRanges,
                navigableAt
        );

        assertEquals(
                List.of(
                        new RoadPlacementPlan.BridgeRange(3, 4),
                        new RoadPlacementPlan.BridgeRange(6, 6)
                ),
                ranges
        );
    }

    @Test
    void plannerSubrangesFeedIntoMixedBridgeClassifications() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(2, 6));
        IntPredicate navigableAt = index -> index == 3 || index == 4;

        List<RoadPlacementPlan.BridgeRange> navigableRanges = RoadCorridorPlanner.detectContiguousSubranges(
                centerPath,
                bridgeRanges,
                navigableAt
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgeRanges, navigableRanges);

        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, plan.slices().get(2).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(3).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(4).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN, plan.slices().get(5).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, plan.slices().get(6).segmentKind());
    }

    @Test
    void plannerLeavesNavigationChannelAbsentWhenNoNavigableMainSpanExists() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(new RoadPlacementPlan.BridgeRange(1, 2)),
                List.of()
        );

        assertNull(plan.navigationChannel());
    }
}
