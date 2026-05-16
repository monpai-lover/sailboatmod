package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerWaterCrossingSplitterTest {
    @Test
    void threeBlockWaterSpanIsIgnoredAsNoise() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(7, 9, 1);

        assertFalse(result.didSplit());
    }

    @Test
    void fourToTwentyFourBlockWaterSpanCreatesSmallBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(6, 13, 1);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void wideWaterSpanCreatesMajorBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(4, 32, 1);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void narrowDeepWaterSpanCreatesSmallBridgeBecauseDepthDoesNotForcePiers() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(8, 13, 12);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    @Test
    void oneBlockLandIslandInsideWaterCrossingStaysOneBridgeRange() {
        RoadPlannerBridgeRuleService.LandProbe landProbe = (x, z) -> x < 6 || x > 18 || x == 12;
        RoadPlannerHeightSampler heightSampler = (x, z) -> 64;
        RoadPlannerWaterDepthProbe depthProbe = (x, z) -> landProbe.isLand(x, z) ? 0 : 2;

        RoadPlannerWaterCrossingSplitter.SplitResult result = RoadPlannerWaterCrossingSplitter.split(
                new BlockPos(0, 64, 0),
                new BlockPos(24, 64, 0),
                landProbe,
                heightSampler,
                depthProbe
        );

        assertTrue(result.didSplit());
        long bridgeNodeCount = result.nodes().stream()
                .filter(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL)
                .count();
        assertTrue(bridgeNodeCount >= 2, "bridge nodes should continue across the one-block island");
        assertEquals(1, countSmallBridgeRuns(result), "one-block island should not split the bridge range");
        assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
    }

    private static int countSmallBridgeRuns(RoadPlannerWaterCrossingSplitter.SplitResult result) {
        int bridgeRuns = 0;
        boolean wasInBridgeRun = false;
        for (RoadPlannerWaterCrossingSplitter.SplitNode node : result.nodes()) {
            boolean isInBridgeRun = node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL;
            if (isInBridgeRun && !wasInBridgeRun) {
                bridgeRuns++;
            }
            wasInBridgeRun = isInBridgeRun;
        }
        return bridgeRuns;
    }

    private static RoadPlannerWaterCrossingSplitter.SplitResult splitAcrossWater(int waterStartX, int waterEndX, int waterDepth) {
        RoadPlannerBridgeRuleService.LandProbe landProbe = (x, z) -> x < waterStartX || x > waterEndX;
        RoadPlannerHeightSampler heightSampler = (x, z) -> 64;
        RoadPlannerWaterDepthProbe depthProbe = (x, z) -> landProbe.isLand(x, z) ? 0 : waterDepth;
        return RoadPlannerWaterCrossingSplitter.split(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                landProbe,
                heightSampler,
                depthProbe
        );
    }
}
