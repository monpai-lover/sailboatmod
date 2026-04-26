package com.monpai.sailboatmod.roadplanner.bridge;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PierBridgeRampGroundingTest {
    @Test
    void acceptsSolidEndpointsAndShiftsWaterOrAirTowardLand() {
        Predicate<BlockPos> solid = pos -> pos.getX() <= 0;
        PierBridgeRampGrounding grounding = new PierBridgeRampGrounding(solid, 24);

        assertEquals(new BlockPos(0, 64, 0), grounding.ground(new BlockPos(0, 64, 0), new BlockPos(-1, 64, 0)).position().orElseThrow());
        assertEquals(new BlockPos(0, 64, 0), grounding.ground(new BlockPos(4, 64, 0), new BlockPos(-1, 64, 0)).position().orElseThrow());
    }

    @Test
    void returnsBlockingIssueWhenNoLandFound() {
        PierBridgeRampGrounding.Result result = new PierBridgeRampGrounding(pos -> false, 24)
                .ground(new BlockPos(4, 64, 0), new BlockPos(-1, 64, 0));

        assertTrue(result.position().isEmpty());
        assertTrue(result.issue().orElseThrow().blocking());
    }
}
