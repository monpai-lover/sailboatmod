package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerBuildStepCompilerTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void manualTwoNodeRoadCreatesSurfaceSteps() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
    }

    @Test
    void majorBridgeCreatesDeckStepsWithoutWaterSurfaceRoad() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
    }

    @Test
    void duplicateNodesDoNotCollapseRouteIntoDuplicatedUselessSteps() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 64, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );

        long distinctPositions = steps.stream()
                .map(BuildStep::pos)
                .distinct()
                .count();
        long distinctStepKeys = steps.stream()
                .map(step -> step.pos().asLong() + ":" + step.phase() + ":" + step.state())
                .distinct()
                .count();

        assertTrue(distinctPositions > 1);
        assertTrue(distinctStepKeys == steps.size());
    }

    @Test
    void skippedDuplicateSegmentDoesNotStealNextSegmentType() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 64, 0)
                ),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }

    @Test
    void mixedBridgeFallbackSegmentsCompileAsDeckSteps() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(4, 64, 0),
                        new BlockPos(8, 64, 0)
                ),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
    }
    @Test
    void nullNodeGapKeepsSeparatedRoadSpansAsSurfaceOnly() {
        List<BuildStep> steps = RoadPlannerBuildStepCompiler.compileForTest(
                java.util.Arrays.asList(
                        new BlockPos(0, 64, 0),
                        new BlockPos(4, 64, 0),
                        null,
                        new BlockPos(8, 64, 0),
                        new BlockPos(12, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS
        );

        assertFalse(steps.isEmpty());
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE));
        assertTrue(steps.stream().anyMatch(step -> step.phase() == BuildPhase.SURFACE && step.pos().getX() == 12));
        assertFalse(steps.stream().anyMatch(step -> step.phase() == BuildPhase.DECK));
    }
}


