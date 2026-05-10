package com.monpai.sailboatmod.roadplanner.map;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadMapRoutePreloadPlannerTest {
    @Test
    void rectangleModeUsesBoundingRectangleWhenBudgetAllows() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 256),
                new BlockPos(512, 64, 256)));

        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE, plan.coverageMode());
        assertTrue(plan.chunks().size() > 0);
    }

    @Test
    void oversizedRectangleFallsBackToPathChunks() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4096, 64, 0)));

        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, plan.coverageMode());
    }

    @Test
    void tileKeysDeduplicateRepeatedRouteChunks() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(256, 64, 0),
                new BlockPos(256, 64, 0)));

        var keys = plan.tileKeys("world_a", "minecraft:overworld", MapLod.LOD_1);
        assertEquals(keys.size(), new java.util.LinkedHashSet<>(keys).size());
    }

    @Test
    void selectionPlanUsesUnpaddedSelectedRectangle() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(4096, 4, 3, 8);

        RoadMapRoutePreloadPlan plan = planner.planSelection(
                new BlockPos(0, 64, 0),
                new BlockPos(48, 64, 48));

        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE, plan.coverageMode());
        assertEquals(16, plan.chunks().size());
    }
}
