package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerNodeHitTesterTest {
    @Test
    void selectsNearestNodeWithinRadius() {
        RoadPlannerNodeHitTester hitTester = new RoadPlannerNodeHitTester(8.0D);

        RoadPlannerNodeSelection selection = hitTester.hitNode(List.of(new BlockPos(0, 64, 0), new BlockPos(30, 64, 0)), 32, 2).orElseThrow();

        assertEquals(1, selection.nodeIndex());
    }

    @Test
    void ignoresNodeOutsideRadius() {
        RoadPlannerNodeHitTester hitTester = new RoadPlannerNodeHitTester(4.0D);

        assertTrue(hitTester.hitNode(List.of(new BlockPos(0, 64, 0)), 10, 0).isEmpty());
    }
}
