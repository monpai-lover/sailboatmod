package com.monpai.sailboatmod.nation.service;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoadPathPostProcessorTest {
    @Test
    void simplifyPathRemovesRedundantStraightInteriorNodes() {
        List<BlockPos> simplified = RoadPathPostProcessor.simplifyPathForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(3, 64, 0)
                ),
                simplified
        );
    }

    @Test
    void straightenBridgeRunKeepsBridgeColumnsOnSingleLine() {
        List<BlockPos> straightened = RoadPathPostProcessor.straightenBridgeRunsForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 1),
                        new BlockPos(2, 64, 2),
                        new BlockPos(3, 64, 1),
                        new BlockPos(4, 64, 0)
                ),
                new boolean[] {false, true, true, true, false}
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0)
                ),
                straightened
        );
    }

    @Test
    void relaxPathSkippingBridgeSmoothsOnlyNonBridgeInteriorNodes() {
        List<BlockPos> relaxed = RoadPathPostProcessor.relaxPathSkippingBridgeForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 2),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0)
                ),
                new boolean[] {false, false, false, true, false}
        );

        assertEquals(new BlockPos(1, 64, 1), relaxed.get(1));
        assertEquals(new BlockPos(3, 64, 0), relaxed.get(3));
    }
}
