package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerManualTerrainRulesTest {
    @Test
    void zeroDepthUnknownTerrainStaysPassableForManualRoadTools() {
        assertTrue(RoadPlannerManualTerrainRules.isRoadPassable(
                16,
                0,
                (x, z) -> false,
                (x, z) -> 0));
    }

    @Test
    void realWaterStillRequiresBridgeForManualRoadTools() {
        assertFalse(RoadPlannerManualTerrainRules.isRoadPassable(
                16,
                0,
                (x, z) -> false,
                (x, z) -> 3));
    }

    @Test
    void deepCanyonBetweenLandAnchorsRequiresBridge() {
        assertTrue(RoadPlannerManualTerrainRules.requiresBridgeForSpan(
                new BlockPos(0, 80, 0),
                new BlockPos(64, 80, 0),
                (x, z) -> true,
                (x, z) -> 0,
                (x, z) -> x >= 24 && x <= 40 ? 38 : 80));
    }

    @Test
    void steepMountainPeakBetweenLandAnchorsRequiresBridge() {
        assertTrue(RoadPlannerManualTerrainRules.requiresBridgeForSpan(
                new BlockPos(0, 70, 0),
                new BlockPos(64, 70, 0),
                (x, z) -> true,
                (x, z) -> 0,
                (x, z) -> x >= 24 && x <= 40 ? 118 : 70));
    }

    @Test
    void gradualHillBetweenLandAnchorsStaysRoad() {
        assertFalse(RoadPlannerManualTerrainRules.requiresBridgeForSpan(
                new BlockPos(0, 70, 0),
                new BlockPos(96, 82, 0),
                (x, z) -> true,
                (x, z) -> 0,
                (x, z) -> 70 + Math.max(0, Math.min(12, x / 8))));
    }

    @Test
    void bridgeNormalizerDoesNotPromoteZeroDepthUnknownManualRoadToBridge() {
        RoadPlannerBridgeSegmentNormalizer.Result normalized = RoadPlannerBridgeSegmentNormalizer.normalize(
                List.of(new BlockPos(0, 64, 0), new BlockPos(32, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                (x, z) -> RoadPlannerManualTerrainRules.isRoadPassable(
                        x,
                        z,
                        (probeX, probeZ) -> false,
                        (probeX, probeZ) -> 0));

        assertEquals(List.of(RoadPlannerSegmentType.ROAD), normalized.segmentTypes());
    }

    @Test
    void manualConnectionKeepsZeroDepthUnknownTerrainAsRoad() {
        RoadPlannerSegmentType type = RoadPlannerManualTerrainRules.segmentTypeForConnection(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0),
                RoadPlannerSegmentType.ROAD,
                (x, z) -> false,
                (x, z) -> 0,
                (x, z) -> 64);

        assertEquals(RoadPlannerSegmentType.ROAD, type);
    }

    @Test
    void manualConnectionStillPromotesCanyonSpanToBridge() {
        RoadPlannerSegmentType type = RoadPlannerManualTerrainRules.segmentTypeForConnection(
                new BlockPos(0, 80, 0),
                new BlockPos(64, 80, 0),
                RoadPlannerSegmentType.ROAD,
                (x, z) -> true,
                (x, z) -> 0,
                (x, z) -> x >= 24 && x <= 40 ? 38 : 80);

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, type);
    }
}
