package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRouteHitTesterTest {
    @Test
    void oneNodeNearHitReturnsEmpty() {
        RoadPlannerRouteHitTester hitTester = new RoadPlannerRouteHitTester(8.0D, 6.0D);

        assertTrue(hitTester.hit(List.of(new BlockPos(0, 64, 0)), 0, 0).isEmpty());
    }
}
