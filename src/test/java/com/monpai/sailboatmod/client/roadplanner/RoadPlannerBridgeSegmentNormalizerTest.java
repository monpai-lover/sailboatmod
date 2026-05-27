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
    void waterSurfaceNodeForcesAdjacentRoadSegmentsToBridge() {
        BlockPos landBefore = new BlockPos(0, 64, 0);
        BlockPos waterSurface = new BlockPos(8, 63, 0);
        BlockPos landAfter = new BlockPos(16, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(landBefore, waterSurface, landAfter),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                (x, z) -> x != 8
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(1));
        assertEquals(1, result.bridgeRanges().size());
        assertEquals(0, result.bridgeRanges().get(0).startSegmentIndex());
        assertEquals(2, result.bridgeRanges().get(0).endSegmentIndexExclusive());
    }

    @Test
    void smallBridgeRangeIsNormalizedWithLandAnchors() {
        BlockPos landBefore = new BlockPos(0, 64, 0);
        BlockPos bridgeStart = new BlockPos(8, 65, 0);
        BlockPos bridgeEnd = new BlockPos(16, 65, 0);
        BlockPos landAfter = new BlockPos(24, 64, 0);

        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(landBefore, bridgeStart, bridgeEnd, landAfter),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_SMALL, RoadPlannerSegmentType.ROAD),
                (x, z) -> x == 0 || x == 24
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(0));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.BRIDGE_SMALL, result.segmentTypes().get(2));
        assertEquals(1, result.bridgeRanges().size());
    }

    @Test
    void longLandConnectorBetweenBridgeSpansStaysRoad() {
        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(16, 64, 0),
                        new BlockPos(48, 64, 0),
                        new BlockPos(80, 64, 0),
                        new BlockPos(96, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR
                ),
                (x, z) -> true
        );

        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(2));
    }

    @Test
    void pollutedLongLandConnectorInsideBridgeRunIsRestoredToRoad() {
        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(20, 64, 0),
                        new BlockPos(41, 64, 0),
                        new BlockPos(99, 64, 0),
                        new BlockPos(120, 64, 0),
                        new BlockPos(144, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR
                ),
                (x, z) -> x < 20 || (x > 40 && x < 100) || x > 120
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(1));
        assertEquals(RoadPlannerSegmentType.ROAD, result.segmentTypes().get(2));
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(3));
    }

    @Test
    void tinyInternalLandIslandBetweenBridgeSpansStaysBridge() {
        RoadPlannerBridgeSegmentNormalizer.Result result = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(16, 64, 0),
                        new BlockPos(18, 64, 0),
                        new BlockPos(32, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR
                ),
                (x, z) -> true
        );

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, result.segmentTypes().get(1));
    }
}
