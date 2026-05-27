package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

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
    void wideWaterSpanKeepsBridgeControlNodesSparse() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(4, 32, 1);

        long bridgeNodes = result.nodes().stream()
                .filter(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR)
                .count();

        assertTrue(bridgeNodes <= 3, "wide bridge split should not add dense redundant control nodes: " + result.nodes());
        assertTrue(result.nodes().size() <= 6, "split node list should stay sparse enough for manual editing: " + result.nodes());
    }

    @Test
    void narrowDeepWaterSpanCreatesSmallBridge() {
        RoadPlannerWaterCrossingSplitter.SplitResult result = splitAcrossWater(8, 13, 3);

        assertTrue(result.didSplit());
        assertTrue(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertFalse(result.nodes().stream().anyMatch(node -> node.segmentType() == RoadPlannerSegmentType.BRIDGE_MAJOR));
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
