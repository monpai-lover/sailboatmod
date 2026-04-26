package com.monpai.sailboatmod.roadplanner.weaver;

import com.monpai.sailboatmod.roadplanner.weaver.pathfinding.WeaverPathPostProcessor;
import com.monpai.sailboatmod.roadplanner.weaver.pathfinding.WeaverSplineHelper;
import com.monpai.sailboatmod.roadplanner.weaver.terrain.WeaverHeightProfileService;
import com.monpai.sailboatmod.roadplanner.weaver.terrain.WeaverRoadHeightInterpolator;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WeaverPathHeightTest {
    @Test
    void splinePostProcessorPreservesFirstAndLastAnchors() {
        List<BlockPos> anchors = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 65, 4),
                new BlockPos(16, 64, 0));

        List<BlockPos> processed = WeaverPathPostProcessor.smoothAnchors(anchors, 2);

        assertEquals(anchors.get(0), processed.get(0));
        assertEquals(anchors.get(anchors.size() - 1), processed.get(processed.size() - 1));
        assertTrue(processed.size() > anchors.size());
    }

    @Test
    void catmullRomReturnsMiddleSegmentEndpoints() {
        WeaverSplineHelper.Vec2d start = WeaverSplineHelper.catmullRomSpline(0, 0, 4, 0, 8, 4, 12, 4, 0.0D);
        WeaverSplineHelper.Vec2d end = WeaverSplineHelper.catmullRomSpline(0, 0, 4, 0, 8, 4, 12, 4, 1.0D);

        assertEquals(new WeaverSplineHelper.Vec2d(4, 0), start);
        assertEquals(new WeaverSplineHelper.Vec2d(8, 4), end);
    }

    @Test
    void heightSmoothingReducesSingleBlockSpikeWithoutMovingEndpoints() {
        int[] heights = {64, 64, 72, 64, 64};

        int[] smoothed = WeaverHeightProfileService.smoothHeights(heights, 1, 2);

        assertEquals(64, smoothed[0]);
        assertEquals(64, smoothed[smoothed.length - 1]);
        assertTrue(smoothed[2] < 72);
        assertTrue(Math.abs(smoothed[2] - smoothed[1]) <= 2);
        assertTrue(Math.abs(smoothed[3] - smoothed[2]) <= 2);
    }

    @Test
    void interpolatorProjectsOntoNearestSegment() {
        List<BlockPos> centers = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 70, 0));
        int[] targetY = {64, 70};

        assertEquals(67, WeaverRoadHeightInterpolator.getInterpolatedY(5, 3, centers, targetY));
    }
}
