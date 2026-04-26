package com.monpai.sailboatmod.roadplanner.weaver;

import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeBuilder;
import com.monpai.sailboatmod.roadplanner.weaver.bridge.WeaverBridgeRangeCalculator;
import com.monpai.sailboatmod.roadplanner.weaver.highway.WeaverHighwayHeightSmoother;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverRoadSpan;
import com.monpai.sailboatmod.roadplanner.weaver.model.WeaverSpanType;
import com.monpai.sailboatmod.roadplanner.weaver.placement.WeaverBuildCandidate;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaverBridgeHighwayTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void bridgeRangeCalculatorDetectsMarkedCenterlineSection() {
        List<BlockPos> centers = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(12, 64, 0));
        List<WeaverRoadSpan> spans = List.of(new WeaverRoadSpan(centers.get(1), centers.get(2), WeaverSpanType.BRIDGE));

        WeaverBridgeRangeCalculator.RangeResult result = WeaverBridgeRangeCalculator.compute(centers, spans);

        assertArrayEquals(new boolean[]{false, true, true, false}, result.isBridge());
        assertEquals(1, result.mergedRanges().size());
        assertEquals(1, result.mergedRanges().get(0).startIndex());
        assertEquals(2, result.mergedRanges().get(0).endIndex());
    }

    @Test
    void bridgeBuilderKeepsDeckPositionsInsideRoadWidth() {
        List<BlockPos> centers = List.of(new BlockPos(0, 70, 0), new BlockPos(1, 70, 0), new BlockPos(2, 70, 0));

        List<WeaverBuildCandidate> deck = WeaverBridgeBuilder.buildDeck(centers, 5, Blocks.STONE_BRICKS.defaultBlockState());

        assertEquals(15, deck.size());
        assertTrue(deck.stream().allMatch(WeaverBuildCandidate::visible));
        assertTrue(deck.stream().allMatch(candidate -> Math.abs(candidate.pos().getZ()) <= 2));
        assertTrue(deck.stream().allMatch(candidate -> candidate.pos().getY() == 70));
    }

    @Test
    void highwayHeightSmootherLimitsNonBridgeSlopeAndPreservesBridge() {
        List<BlockPos> centers = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0));
        int[] heights = {64, 80, 80};
        boolean[] isBridge = {false, false, true};

        int[] smoothed = WeaverHighwayHeightSmoother.smooth(heights, centers, isBridge, 4, 4);

        assertEquals(64, smoothed[0]);
        assertEquals(68, smoothed[1]);
        assertEquals(80, smoothed[2]);
    }
}
