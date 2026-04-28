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
        assertTrue(expanded.segmentTypes().stream().anyMatch(type -> type == RoadPlannerSegmentType.BRIDGE_MAJOR));
        assertTrue(RoadPlannerBuildControlService.previewBuildSteps(expanded.nodes(), expanded.segmentTypes(), RoadPlannerBuildSettings.DEFAULTS, null)
                .stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }
}
