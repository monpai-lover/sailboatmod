package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Collections;
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
        assertTrue(plan.slices().get(5).supportPositions().isEmpty());
        assertTrue(plan.slices().get(3).supportPositions().isEmpty());
        assertEquals(3, plan.navigationChannel().min().getX());
        assertEquals(4, plan.navigationChannel().max().getX());
    }

    @Test
    void plannerAllowsSupportlessProtectedMainChannelWhenNoPierSlotsExist() {
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
        List<RoadPlacementPlan.BridgeRange> navigableRanges = List.of(new RoadPlacementPlan.BridgeRange(3, 5));

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgeRanges, navigableRanges);

        assertTrue(plan.valid());
        assertTrue(plan.slices().get(3).supportPositions().isEmpty());
        assertTrue(plan.slices().get(4).supportPositions().isEmpty());
        assertTrue(plan.slices().get(5).supportPositions().isEmpty());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(4).segmentKind());
    }

    @Test
    void plannerRepositionsSupportsAwayFromProtectedMainChannelWhenBridgeHasOtherSupportOptions() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0)
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(1, 8));
        List<RoadPlacementPlan.BridgeRange> navigableRanges = List.of(new RoadPlacementPlan.BridgeRange(4, 4));

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgeRanges, navigableRanges);

        assertTrue(plan.valid());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(4).segmentKind());
        assertTrue(plan.slices().get(4).supportPositions().isEmpty());
        assertTrue(plan.slices().stream()
                .filter(slice -> slice.segmentKind() == RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN)
                .anyMatch(slice -> !slice.supportPositions().isEmpty()));
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

    @Test
    void plannerUsesProvidedPlacementHeightsForRaisedBridgeDecks() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(new RoadPlacementPlan.BridgeRange(1, 3)),
                List.of(new RoadPlacementPlan.BridgeRange(2, 2)),
                new int[] {65, 67, 70, 67, 65}
        );

        assertEquals(67, plan.slices().get(1).deckCenter().getY());
        assertEquals(70, plan.slices().get(2).deckCenter().getY());
        assertEquals(67, plan.slices().get(3).deckCenter().getY());
        assertTrue(plan.slices().get(2).surfacePositions().stream().allMatch(pos -> pos.getY() >= 70));
        assertTrue(plan.slices().get(1).surfacePositions().stream().anyMatch(pos -> pos.getY() == 67));
    }

    @Test
    void archSpanSlicesCarryArchModeAndNeverEmitSupportColumns() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 67, 0),
                new BlockPos(3, 65, 0),
                new BlockPos(4, 64, 0)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        3,
                        RoadBridgePlanner.BridgeMode.ARCH_SPAN,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 65, 0), new BlockPos(1, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(3, new BlockPos(3, 65, 0), new BlockPos(3, 62, 0), 65, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN, 65, 65)),
                        65,
                        false,
                        true
                )
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 65, 67, 65, 65});

        assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.slices().get(2).bridgeMode());
        assertTrue(plan.slices().stream().allMatch(slice -> slice.supportPositions().isEmpty()));
    }

    @Test
    void pierBridgeSlicesEmitSupportOnlyAtExplicitPierNodeIndexes() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 0),
                new BlockPos(4, 68, 0),
                new BlockPos(5, 66, 0),
                new BlockPos(6, 64, 0)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        5,
                        RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 66, 0), new BlockPos(1, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(2, new BlockPos(2, 68, 0), new BlockPos(2, 40, 0), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(4, new BlockPos(4, 68, 0), new BlockPos(4, 40, 0), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(5, new BlockPos(5, 66, 0), new BlockPos(5, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(
                                new RoadBridgePlanner.BridgeDeckSegment(1, 2, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP, 66, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(2, 4, RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL, 68, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(4, 5, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN, 68, 66)
                        ),
                        68,
                        true,
                        true
                )
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 66, 68, 68, 68, 66, 65});

        assertFalse(plan.slices().get(2).supportPositions().isEmpty());
        assertTrue(plan.slices().get(3).supportPositions().isEmpty());
        assertFalse(plan.slices().get(4).supportPositions().isEmpty());
        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.slices().get(3).bridgeMode());
    }

    @Test
    void plannerBuildsBridgeDeckAtWaterSurfacePlusFiveAndPopulatesRailingLights() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(new RoadPlacementPlan.BridgeRange(1, 3)),
                List.of(new RoadPlacementPlan.BridgeRange(1, 3)),
                new int[] {64, 68, 68, 68, 64}
        );

        assertEquals(68, plan.slices().get(1).deckCenter().getY());
        assertEquals(68, plan.slices().get(2).deckCenter().getY());
        assertEquals(68, plan.slices().get(3).deckCenter().getY());
        assertFalse(plan.slices().get(2).railingLightPositions().isEmpty());
    }

    @Test
    void defaultLandSlicesTrackSurfaceReplacementAtTerrainHeightAndTwoBlockClearanceEnvelope() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 65, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, List.of(), List.of());

        assertTrue(plan.slices().get(0).surfacePositions().contains(new BlockPos(0, 64, 0)));
        assertTrue(plan.slices().get(0).clearancePositions().contains(new BlockPos(0, 65, 0)));
        assertTrue(plan.slices().get(0).clearancePositions().contains(new BlockPos(0, 66, 0)));
        assertFalse(plan.slices().get(0).surfacePositions().contains(new BlockPos(0, 65, 0)));
    }

    @Test
    void plannerBuildsElevatedApproachesForHighReliefRiverCrossing() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 74, 0),
                new BlockPos(6, 74, 0),
                new BlockPos(7, 74, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(new RoadPlacementPlan.BridgeRange(2, 5)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 4)),
                new int[] {65, 66, 68, 70, 70, 72, 74, 75}
        );

        assertEquals(RoadCorridorPlan.SegmentKind.APPROACH_RAMP, plan.slices().get(1).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.ELEVATED_APPROACH, plan.slices().get(2).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, plan.slices().get(3).segmentKind());
        assertTrue(hasSurfaceClosure(plan.slices().get(2).surfacePositions(), plan.slices().get(3).surfacePositions()));
        assertTrue(plan.slices().get(2).surfacePositions().stream().anyMatch(pos -> pos.getX() == 2));
        assertTrue(plan.slices().get(2).excavationPositions().stream().anyMatch(pos -> pos.getX() == 2));
        assertTrue(plan.slices().get(2).clearancePositions().stream().anyMatch(pos -> pos.getX() == 2));
        assertTrue(plan.slices().get(3).surfacePositions().stream().anyMatch(pos -> pos.getX() == 2));
    }

    @Test
    void plannerAddsExcavationAndClearanceForBuriedApproachColumns() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 66, 0)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(),
                List.of(),
                new int[] {65, 66, 67}
        );

        assertFalse(plan.slices().get(1).clearancePositions().isEmpty());
        assertFalse(plan.slices().get(1).excavationPositions().isEmpty());
    }

    @Test
    void plannerAddsTongDaWayStyleStreetlightsAlongLongLandApproachSegments() {
        List<BlockPos> centerPath = java.util.stream.IntStream.rangeClosed(0, 48)
                .mapToObj(x -> new BlockPos(x, 64, 0))
                .toList();

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, List.of(), List.of());

        assertTrue(plan.slices().get(24).railingLightPositions().size() == 1,
                () -> String.valueOf(plan.slices().get(24).railingLightPositions()));
        assertTrue(plan.slices().get(48).railingLightPositions().size() == 1,
                () -> String.valueOf(plan.slices().get(48).railingLightPositions()));
        assertTrue(plan.slices().get(12).railingLightPositions().isEmpty());
        assertTrue(plan.slices().get(23).railingLightPositions().isEmpty());
        assertTrue(plan.slices().get(25).railingLightPositions().isEmpty());
    }

    @Test
    void plannerPreservesPerRangeBridgeHeadsForAdjacentAndOverlappingBridgeRanges() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0)
        );

        RoadCorridorPlan adjacentPlan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(
                        new RoadPlacementPlan.BridgeRange(2, 3),
                        new RoadPlacementPlan.BridgeRange(4, 5)
                ),
                List.of()
        );
        RoadCorridorPlan overlappingPlan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(
                        new RoadPlacementPlan.BridgeRange(2, 4),
                        new RoadPlacementPlan.BridgeRange(4, 6)
                ),
                List.of()
        );

        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, adjacentPlan.slices().get(2).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, adjacentPlan.slices().get(3).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, adjacentPlan.slices().get(4).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, adjacentPlan.slices().get(5).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, overlappingPlan.slices().get(2).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, overlappingPlan.slices().get(4).segmentKind());
        assertEquals(RoadCorridorPlan.SegmentKind.BRIDGE_HEAD, overlappingPlan.slices().get(6).segmentKind());
    }

    @Test
    void plannerDistributesLongBridgeSupportsAcrossWholeElevatedSpan() {
        List<BlockPos> centerPath = java.util.stream.IntStream.rangeClosed(0, 8)
                .mapToObj(x -> new BlockPos(x, 64, 0))
                .toList();

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(
                centerPath,
                List.of(new RoadPlacementPlan.BridgeRange(1, 7)),
                List.of()
        );

        assertEquals(
                List.of(4),
                plan.slices().stream()
                        .filter(slice -> !slice.supportPositions().isEmpty())
                        .map(RoadCorridorPlan.CorridorSlice::index)
                        .toList()
        );
    }

    @Test
    void plannerClosesSurfaceGapsAcrossFlatCurvedAdjacentSlices() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 2),
                new BlockPos(4, 64, 2),
                new BlockPos(5, 64, 2)
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, List.of(), List.of());

        for (int i = 0; i < plan.slices().size() - 1; i++) {
            int sliceIndex = i;
            assertTrue(
                    hasSurfaceClosure(plan.slices().get(sliceIndex).surfacePositions(), plan.slices().get(sliceIndex + 1).surfacePositions()),
                    () -> "surface gap between slices " + sliceIndex + " and " + (sliceIndex + 1)
                            + " current=" + plan.slices().get(sliceIndex).surfacePositions()
                            + " next=" + plan.slices().get(sliceIndex + 1).surfacePositions()
            );
        }
    }

    @Test
    void plannerClosesSharpPierBridgeTurnWithoutFragmentingOuterShoulder() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 1),
                new BlockPos(4, 68, 2),
                new BlockPos(4, 66, 3),
                new BlockPos(4, 64, 4)
        );
        List<RoadBridgePlanner.BridgeSpanPlan> bridgePlans = List.of(
                new RoadBridgePlanner.BridgeSpanPlan(
                        1,
                        5,
                        RoadBridgePlanner.BridgeMode.PIER_BRIDGE,
                        List.of(
                                new RoadBridgePlanner.BridgePierNode(1, new BlockPos(1, 66, 0), new BlockPos(1, 63, 0), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT),
                                new RoadBridgePlanner.BridgePierNode(3, new BlockPos(3, 68, 1), new BlockPos(3, 40, 1), 68, RoadBridgePlanner.BridgeNodeRole.PIER),
                                new RoadBridgePlanner.BridgePierNode(5, new BlockPos(4, 66, 3), new BlockPos(4, 63, 3), 66, RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
                        ),
                        List.of(
                                new RoadBridgePlanner.BridgeDeckSegment(1, 3, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_UP, 66, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(3, 3, RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL, 68, 68),
                                new RoadBridgePlanner.BridgeDeckSegment(3, 5, RoadBridgePlanner.BridgeDeckSegmentType.APPROACH_DOWN, 68, 66)
                        ),
                        68,
                        false,
                        true
                )
        );

        RoadCorridorPlan plan = RoadCorridorPlanner.plan(centerPath, bridgePlans, new int[] {65, 66, 68, 68, 68, 66, 65});

        for (int i = 0; i < plan.slices().size() - 1; i++) {
            int sliceIndex = i;
            assertTrue(
                    hasSurfaceClosure(plan.slices().get(sliceIndex).surfacePositions(), plan.slices().get(sliceIndex + 1).surfacePositions()),
                    () -> "fragmented bridge turn between slices " + sliceIndex + " and " + (sliceIndex + 1)
                            + " current=" + plan.slices().get(sliceIndex).surfacePositions()
                            + " next=" + plan.slices().get(sliceIndex + 1).surfacePositions()
            );
        }
        assertTrue(
                surfaceOverlapCount(plan.slices().get(2).surfacePositions(), plan.slices().get(3).surfacePositions()) >= 7,
                () -> "expected a filled closure band across the sharp bridge turn, got only "
                        + surfaceOverlapCount(plan.slices().get(2).surfacePositions(), plan.slices().get(3).surfacePositions())
                        + " shared columns"
        );
    }

    private static boolean hasSurfaceClosure(List<BlockPos> current, List<BlockPos> next) {
        if (!Collections.disjoint(current, next)) {
            return true;
        }
        for (BlockPos left : current) {
            for (BlockPos right : next) {
                if (Math.abs(left.getY() - right.getY()) > 2) {
                    continue;
                }
                int horizontalDistance = Math.abs(left.getX() - right.getX()) + Math.abs(left.getZ() - right.getZ());
                if (horizontalDistance <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int surfaceOverlapCount(List<BlockPos> current, List<BlockPos> next) {
        int count = 0;
        for (BlockPos pos : current) {
            if (next.stream().anyMatch(other -> other.getX() == pos.getX() && other.getZ() == pos.getZ())) {
                count++;
            }
        }
        return count;
    }
}
