package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.roadplanner.service.RoadPlannerBuildControlService;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RoadPlannerRouteExpanderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void manualTwoPointRoadExpandsLikeAutoCompleteAndBuildsSteps() {
        RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                (x, z) -> true,
                (x, z) -> 64
        );

        assertTrue(expanded.success());
        assertTrue(expanded.nodes().size() > 2);
        assertEquals(expanded.nodes().size() - 1, expanded.segmentTypes().size());
        assertTrue(RoadPlannerBuildControlService.previewBuildSteps(expanded.nodes(), expanded.segmentTypes(), RoadPlannerBuildSettings.DEFAULTS, null).size() > 20);
    }

    @Test
    void bezierLikeSparseNodesExpandIntoBuildableRoadStructure() {
        RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(18, 64, 12), new BlockPos(40, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                (x, z) -> true,
                (x, z) -> 64
        );

        assertTrue(expanded.success());
        assertTrue(expanded.nodes().size() > 3);
        assertTrue(RoadPlannerBuildControlService.previewBuildSteps(expanded.nodes(), expanded.segmentTypes(), RoadPlannerBuildSettings.DEFAULTS, null)
                .stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
    }

    @Test
    void waterCrossingSplitsRoadIntoBridgeSegmentsBeforeBuild() {
        RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                (x, z) -> x < 12 || x > 28,
                (x, z) -> 64
        );

        assertTrue(expanded.success());
        assertTrue(expanded.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL));
        assertTrue(RoadPlannerBuildControlService.previewBuildSteps(expanded.nodes(), expanded.segmentTypes(), RoadPlannerBuildSettings.DEFAULTS, null)
                .stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }
    @Test
    void sparseRoadCrossingWaterPromotesGeneratedMiddleSegmentsToBridge() {
        RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                (x, z) -> x < 13 || x > 27,
                (x, z) -> 64
        );

        assertTrue(expanded.success());
        assertTrue(expanded.segmentTypes().stream().anyMatch(RoadPlannerRouteExpanderTest::isBridge));
        for (int index = 0; index < expanded.segmentTypes().size(); index++) {
            if (segmentTouchesWater(expanded.nodes().get(index), expanded.nodes().get(index + 1), x -> x < 13 || x > 27)) {
                assertTrue(isBridge(expanded.segmentTypes().get(index)), "water-touching segment must be bridge at index " + index);
            }
        }
    }

    @Test
    void expandedWaterCrossingKeepsRoadSegmentsOutsideBridgeSpanWherePossible() {
        RoadPlannerRouteExpander.Result expanded = RoadPlannerRouteExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                (x, z) -> x < 20 || x > 28,
                (x, z) -> 64
        );

        assertTrue(expanded.success());
        assertTrue(expanded.segmentTypes().stream().anyMatch(RoadPlannerRouteExpanderTest::isBridge));
        assertFalse(isBridge(expanded.segmentTypes().get(0)), "land approach should remain road");
        assertFalse(isBridge(expanded.segmentTypes().get(expanded.segmentTypes().size() - 1)), "land exit should remain road");
    }

    private static boolean isBridge(RoadPlannerSegmentType type) {
        return type == RoadPlannerSegmentType.BRIDGE_MAJOR || type == RoadPlannerSegmentType.BRIDGE_SMALL;
    }

    private static boolean segmentTouchesWater(BlockPos from, BlockPos to, LandByX landByX) {
        int dx = to.getX() - from.getX();
        int dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(distance / 2.0D));
        for (int step = 0; step <= steps; step++) {
            double t = step / (double) steps;
            int x = (int) Math.round(from.getX() + dx * t);
            if (!landByX.isLand(x)) {
                return true;
            }
        }
        return false;
    }

    private interface LandByX {
        boolean isLand(int x);
    }
}
