package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBridgeSegmentNormalizerTest {
    @Test
    void bridgeRangeKeepsPlayerDirectionAndIncludesLandAnchors() {
        BlockPos landBefore = new BlockPos(0, 64, 0);
        BlockPos bridgeStart = new BlockPos(8, 66, 0);
        BlockPos bridgeMid = new BlockPos(16, 68, 0);
        BlockPos bridgeEnd = new BlockPos(24, 66, 0);
        BlockPos landAfter = new BlockPos(32, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(landBefore, bridgeStart, bridgeMid, bridgeEnd, landAfter),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                (x, z) -> x == 0 || x == 32
        );

        assertEquals(List.of(landBefore, bridgeStart, bridgeMid, bridgeEnd, landAfter), result.nodes());
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(3));
        assertEquals(1, result.bridgeRanges().size());
        assertEquals(0, result.bridgeRanges().get(0).startSegmentIndex());
        assertEquals(4, result.bridgeRanges().get(0).endSegmentIndexExclusive());
    }

    @Test
    void bridgeRangeWithoutLandAnchorsReportsBlockingIssue() {
        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(new BlockPos(8, 66, 0), new BlockPos(16, 68, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                (x, z) -> false
        );

        assertTrue(result.hasBlockingIssues());
    }

    @Test
    void internalRoadSegmentBetweenBridgeSegmentsIsPromotedBackToBridge() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(8, 65, 0);
        BlockPos c = new BlockPos(16, 65, 0);
        BlockPos d = new BlockPos(24, 65, 0);
        BlockPos e = new BlockPos(32, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(a, b, c, d, e),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD
                ),
                (x, z) -> x == 0 || x == 32 || x == 16
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(3));
        assertEquals(1, result.bridgeRanges().size());
        assertEquals(0, result.bridgeRanges().get(0).startSegmentIndex());
        assertEquals(4, result.bridgeRanges().get(0).endSegmentIndexExclusive());
    }

    @Test
    void multipleInternalRoadSegmentsBetweenBridgeSegmentsArePromotedBackToBridge() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(8, 65, 0);
        BlockPos c = new BlockPos(16, 65, 0);
        BlockPos d = new BlockPos(24, 65, 0);
        BlockPos e = new BlockPos(32, 65, 0);
        BlockPos f = new BlockPos(40, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(a, b, c, d, e, f),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD
                ),
                (x, z) -> x == 0 || x == 16 || x == 24 || x == 40
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(3));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(4));
        assertEquals(1, result.bridgeRanges().size());
        assertEquals(0, result.bridgeRanges().get(0).startSegmentIndex());
        assertEquals(5, result.bridgeRanges().get(0).endSegmentIndexExclusive());
    }

    @Test
    void fourInternalRoadSegmentsBetweenBridgeSegmentsArePromotedBackToBridge() {
        List<BlockPos> nodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 65, 0),
                new BlockPos(16, 65, 0),
                new BlockPos(24, 65, 0),
                new BlockPos(32, 65, 0),
                new BlockPos(40, 65, 0),
                new BlockPos(48, 64, 0)
        );

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                nodes,
                List.of(
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_SMALL
                ),
                (x, z) -> true
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(3));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(4));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(5));
        assertEquals(1, result.bridgeRanges().size());
    }

    @Test
    void longRoadGapBetweenBridgeSegmentsStaysRoadAndSplitsBridgeRanges() {
        List<BlockPos> nodes = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 65, 0),
                new BlockPos(16, 65, 0),
                new BlockPos(24, 65, 0),
                new BlockPos(32, 65, 0),
                new BlockPos(40, 65, 0),
                new BlockPos(48, 65, 0),
                new BlockPos(56, 65, 0),
                new BlockPos(64, 64, 0)
        );

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                nodes,
                List.of(
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD
                ),
                (x, z) -> true
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(3));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(4));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(5));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(6));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(7));
        assertEquals(2, result.bridgeRanges().size());
    }

    @Test
    void mixedSmallAndMajorBridgeRangeNormalizesToMajor() {
        BlockPos a = new BlockPos(0, 64, 0);
        BlockPos b = new BlockPos(8, 65, 0);
        BlockPos c = new BlockPos(16, 65, 0);
        BlockPos d = new BlockPos(24, 65, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(a, b, c, d),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_SMALL
                ),
                (x, z) -> true
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(2));
        assertEquals(1, result.bridgeRanges().size());
    }

    @Test
    void endpointRoadSegmentsOutsideBridgeRangeStayRoad() {
        BlockPos a = new BlockPos(-8, 64, 0);
        BlockPos b = new BlockPos(0, 64, 0);
        BlockPos c = new BlockPos(8, 66, 0);
        BlockPos d = new BlockPos(16, 64, 0);
        BlockPos e = new BlockPos(24, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(a, b, c, d, e),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.BRIDGE_SMALL,
                        RoadPlannerSegmentType.ROAD
                ),
                (x, z) -> x <= 0 || x >= 16
        );

        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(3));
    }
}
