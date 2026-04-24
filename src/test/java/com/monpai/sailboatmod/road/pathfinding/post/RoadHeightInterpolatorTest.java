package com.monpai.sailboatmod.road.pathfinding.post;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadHeightInterpolatorTest {
    @Test
    void projectsWidthCellsOntoNearbyCenterSegment() {
        List<BlockPos> centers = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(10, 68, 0));
        int[] targetY = {64, 68};

        assertEquals(66, RoadHeightInterpolator.getInterpolatedY(5, 2, centers, targetY));
    }

    @Test
    void batchInterpolateUsesSegmentHintAndProjection() {
        List<BlockPos> centers = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(10, 68, 0),
                new BlockPos(20, 68, 0));
        List<BlockPos> positions = List.of(
                new BlockPos(0, 0, 2),
                new BlockPos(5, 0, 2),
                new BlockPos(10, 0, 2));
        int[] targetY = {64, 68, 68};

        assertArrayEquals(new int[]{64, 66, 68},
                RoadHeightInterpolator.batchInterpolate(positions, 0, centers, targetY));
    }
}