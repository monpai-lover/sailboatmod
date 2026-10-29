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

    @Test
    void oversizedExplicitSelectionIsRejectedFromItsAreaEstimateBeforeAnyWorldEnumeration() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(64, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.planSelection(BlockPos.ZERO, new BlockPos(4096, 64, 4096));
        assertTrue(plan.chunks().isEmpty(), "the actual 200000-block GUI case must not allocate its huge rectangle before rejecting it");
        assertEquals(257 * 257, plan.rectangleChunkCount());
    }

    @Test
    void legacyNonGeneratingPathFallbackAlsoRetainsOnlyBoundedLocalWork() {
        RoadMapRoutePreloadPlanner planner = new RoadMapRoutePreloadPlanner(64, 4, 3, 8);
        RoadMapRoutePreloadPlan plan = planner.plan(List.of(BlockPos.ZERO, new BlockPos(4096, 64, 4096)));
        assertEquals(RoadMapRoutePreloadPlan.CoverageMode.PATH_ONLY, plan.coverageMode());
        assertTrue(plan.chunks().size() <= 64, "route fallback must not build the full path list either");
    }
}
