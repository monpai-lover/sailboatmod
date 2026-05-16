package com.monpai.sailboatmod.roadplanner.postprocess;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPathPostProcessorTest {
    @Test
    void simplifyRemovesCollinearMiddleNodes() {
        List<BlockPos> processed = RoadPathPostProcessor.process(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(16, 64, 0),
                new BlockPos(24, 64, 0),
                new BlockPos(32, 64, 0)
        ), new boolean[5]);

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(32, 64, 0)
        ), processed);
    }

    @Test
    void bridgeRunIsStraightenedBetweenLandAnchors() {
        List<BlockPos> processed = RoadPathPostProcessor.process(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 4),
                new BlockPos(8, 64, -4),
                new BlockPos(12, 64, 0)
        ), new boolean[]{false, true, true, false});

        assertEquals(new BlockPos(0, 64, 0), processed.get(0));
        assertEquals(new BlockPos(4, 64, 0), processed.get(1));
        assertEquals(new BlockPos(8, 64, 0), processed.get(2));
        assertEquals(new BlockPos(12, 64, 0), processed.get(3));
    }

    @Test
    void nonBridgeNodesUseWeightedAverageRelaxation() {
        List<BlockPos> relaxed = RoadPathPostProcessor.relax(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 8),
                new BlockPos(16, 64, 0)
        ), new boolean[]{false, false, false});

        assertEquals(new BlockPos(8, 64, 4), relaxed.get(1));
    }

    @Test
    void processKeepsCoarseNodesWhileSplineEntryCanDensifyForFutureBuilderIntegration() {
        List<BlockPos> raw = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 8),
                new BlockPos(16, 64, 0),
                new BlockPos(24, 64, 8)
        );
        boolean[] mask = new boolean[raw.size()];

        List<BlockPos> coarse = RoadPathPostProcessor.process(raw, mask);
        List<BlockPos> spline = RoadPathPostProcessor.processWithSpline(raw, mask);

        assertEquals(raw.get(0), coarse.get(0));
        assertEquals(raw.get(raw.size() - 1), coarse.get(coarse.size() - 1));
        assertEquals(raw.get(0), spline.get(0));
        assertEquals(raw.get(raw.size() - 1), spline.get(spline.size() - 1));
        assertTrue(spline.size() > coarse.size());
    }
}
