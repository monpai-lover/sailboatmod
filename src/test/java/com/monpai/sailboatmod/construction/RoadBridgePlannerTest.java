package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadBridgePlannerTest {
    @Test
    void normalizeRangesDropsTinyBridgeSpans() {
        assertEquals(
                List.of(new RoadPlacementPlan.BridgeRange(7, 9)),
                RoadBridgePlanner.normalizeRangesForTest(
                        List.of(
                                new RoadPlacementPlan.BridgeRange(1, 1),
                                new RoadPlacementPlan.BridgeRange(7, 9)
                        ),
                        10
                )
        );
    }

    @Test
    void normalizeRangesMergesNearbyElevatedSpans() {
        assertEquals(
                List.of(new RoadPlacementPlan.BridgeRange(1, 6)),
                RoadBridgePlanner.normalizeRangesForTest(
                        List.of(
                                new RoadPlacementPlan.BridgeRange(1, 2),
                                new RoadPlacementPlan.BridgeRange(5, 6)
                        ),
                        8
                )
        );
    }

    @Test
    void distributePierAnchorsUsesFullElevatedSpanLength() {
        assertEquals(
                List.of(2, 4, 6),
                RoadBridgePlanner.distributePierAnchorsForTest(
                        1,
                        7,
                        List.of(2, 3, 4, 5, 6)
                )
        );
    }

    @Test
    void navigableBridgeProfileKeepsFiveBlockClearanceAboveWater() {
        RoadBridgePlanner.BridgeProfile profile = RoadBridgePlanner.navigableProfileForTest(10, 20, 64);

        assertEquals(69, profile.deckHeight());
    }

    @Test
    void classifiesNoneWhenNoUnsupportedColumns() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0)
                ),
                index -> false,
                index -> 64
        );

        assertEquals(RoadBridgePlanner.BridgeKind.NONE, kind);
    }

    @Test
    void classifiesFlatWhenUnsupportedColumnsAreThreeOrFewer() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0)
                ),
                index -> true,
                index -> 62
        );

        assertEquals(RoadBridgePlanner.BridgeKind.FLAT, kind);
    }

    @Test
    void classifiesFlatWhenTerrainDropIsBelowSixEvenForLongSpan() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0),
                        new BlockPos(3, 70, 0),
                        new BlockPos(4, 70, 0),
                        new BlockPos(5, 70, 0)
                ),
                index -> true,
                index -> 66
        );

        assertEquals(RoadBridgePlanner.BridgeKind.FLAT, kind);
    }

    @Test
    void classifiesLongUnsafeSpanAsArchedBridge() {
        RoadBridgePlanner.BridgeKind kind = RoadBridgePlanner.classify(
                List.of(
                        new BlockPos(0, 70, 0),
                        new BlockPos(1, 70, 0),
                        new BlockPos(2, 70, 0),
                        new BlockPos(3, 70, 0),
                        new BlockPos(4, 70, 0),
                        new BlockPos(5, 70, 0)
                ),
                index -> true,
                index -> 62
        );

        assertEquals(RoadBridgePlanner.BridgeKind.ARCHED, kind);
    }

    @Test
    void plansShortWaterCrossingAsArchSpanWithoutInteriorPiers() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 3),
                index -> index >= 1 && index <= 3,
                index -> false,
                index -> 62,
                index -> 63,
                index -> true
        );

        assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.mode());
        assertEquals(List.of(1, 3), plan.nodes().stream().map(RoadBridgePlanner.BridgePierNode::pathIndex).toList());
        org.junit.jupiter.api.Assertions.assertTrue(
                plan.nodes().stream().allMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
        );
        assertEquals(
                List.of(RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN),
                plan.deckSegments().stream().map(RoadBridgePlanner.BridgeDeckSegment::type).toList()
        );
    }

    @Test
    void keepsMediumNonNavigableWaterCrossingAsArchSpanWithoutInteriorPiers() {
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

        RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 7),
                index -> index >= 1 && index <= 7,
                index -> false,
                index -> 61,
                index -> 63,
                index -> true
        );

        assertEquals(RoadBridgePlanner.BridgeMode.ARCH_SPAN, plan.mode());
        assertTrue(plan.nodes().stream().allMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.ABUTMENT));
        assertEquals(
                List.of(RoadBridgePlanner.BridgeDeckSegmentType.ARCHED_SPAN),
                plan.deckSegments().stream().map(RoadBridgePlanner.BridgeDeckSegment::type).toList()
        );
    }

    @Test
    void upgradesWideNavigableCrossingToPierBridgeWithChannelPiers() {
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
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 9),
                index -> index >= 1 && index <= 9,
                index -> index >= 4 && index <= 6,
                index -> 40,
                index -> 63,
                index -> true
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.mode());
        org.junit.jupiter.api.Assertions.assertTrue(
                plan.nodes().stream().anyMatch(node -> node.role() != RoadBridgePlanner.BridgeNodeRole.ABUTMENT)
        );
        assertEquals(
                2,
                plan.nodes().stream().filter(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.CHANNEL_PIER).count()
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                plan.deckSegments().stream().anyMatch(segment -> segment.type() == RoadBridgePlanner.BridgeDeckSegmentType.MAIN_LEVEL)
        );
    }

    @Test
    void upgradesEightColumnCrossingToPierBridgeToAvoidArchingFullLongSpan() {
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

        RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 8),
                index -> index >= 1 && index <= 8,
                index -> false,
                index -> 40,
                index -> 63,
                index -> true
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.mode());
        assertTrue(plan.nodes().stream().anyMatch(node -> node.role() == RoadBridgePlanner.BridgeNodeRole.PIER));
    }

    @Test
    void marksPierBridgeInvalidWhenInteriorFoundationSlotsCannotSustainTheCrossing() {
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
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan plan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 9),
                index -> index >= 1 && index <= 9,
                index -> false,
                index -> 40,
                index -> 63,
                index -> index == 1 || index == 9
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, plan.mode());
        org.junit.jupiter.api.Assertions.assertFalse(plan.valid());
    }
}
