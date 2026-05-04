package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNodeStructureExpanderTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void normalizesMissingSegmentsAndSkipsDuplicateSegmentTypes() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 64, 0),
                        new BlockPos(16, 64, 0)
                ),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(16, 64, 0)
        ), result.canonicalNodes());
        assertEquals(List.of(
                RoadPlannerSegmentType.ROAD,
                RoadPlannerSegmentType.ROAD
        ), result.canonicalSegmentTypes());
    }

    @Test
    void blockedBridgeMarkerNormalizesToMajorBridge() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(RoadPlannerSegmentType.BRIDGE_MAJOR), result.canonicalSegmentTypes());
    }

    @Test
    void nullNodeBreaksRouteInsteadOfConnectingAcrossGap() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                Arrays.asList(
                        new BlockPos(0, 64, 0),
                        new BlockPos(4, 64, 0),
                        null,
                        new BlockPos(40, 64, 0),
                        new BlockPos(44, 64, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.hasErrors());
        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(40, 64, 0),
                new BlockPos(44, 64, 0)
        ), result.canonicalNodes());
        assertEquals(List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.ROAD), result.canonicalSegmentTypes());
    }

    @Test
    void singleNodeProducesIssueAndNoSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0)),
                List.of(),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertTrue(result.hasErrors());
        assertEquals(0, result.buildSteps().size());
        assertEquals(0, result.previewBlocks().size());
    }
}
