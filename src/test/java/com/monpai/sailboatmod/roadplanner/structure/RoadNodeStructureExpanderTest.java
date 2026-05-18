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


    @Test
    void buildsContinuousCenterlineAndBridgeSpan() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.PREVIEW
        );

        assertFalse(result.centerline().isEmpty());
        assertEquals(new BlockPos(0, 64, 0), result.centerline().get(0).pos());
        assertEquals(new BlockPos(8, 64, 0), result.centerline().get(result.centerline().size() - 1).pos());
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == RoadSpanType.ROAD));
        assertTrue(result.spans().stream().anyMatch(span -> span.type() == RoadSpanType.BRIDGE));
    }

    @Test
    void steepRoadTerrainIsSmoothedForCartTravel() {
        RoadTerrainSampler steep = (x, z) -> 64 + x * 2;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 80, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                steep,
                RoadStructureMode.PREVIEW
        );

        int maxDelta = 0;
        for (int i = 1; i < result.centerline().size(); i++) {
            maxDelta = Math.max(maxDelta, Math.abs(result.centerline().get(i).targetY() - result.centerline().get(i - 1).targetY()));
        }
        assertTrue(maxDelta <= 1, "integer targetY must not jump more than one block between samples");
        assertTrue(result.centerline().stream().anyMatch(point -> point.targetY() < point.terrainY()));
    }


    @Test
    void flatRoadEmitsFoundationAndSurfaceSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
    }

    @Test
    void northSouthRoadUsesCrossPathWidthInsteadOfLengthwiseResampling() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 8)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(64),
                RoadStructureMode.BUILD
        );

        List<BlockPos> surface = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertTrue(surface.contains(new BlockPos(-2, 64, 0)), "width should extend west of a north-south road");
        assertTrue(surface.contains(new BlockPos(2, 64, 0)), "width should extend east of a north-south road");
        assertFalse(surface.contains(new BlockPos(0, 64, -2)), "width must not extend backwards along the road direction");
    }

    @Test
    void turningRoadSurfaceFillsInsideCornerGap() {
        List<RoadCenterlinePoint> sparseTurn = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.ROAD),
                point(4, 64, 0, 4.0D, RoadPlannerSegmentType.ROAD),
                point(4, 64, 4, 8.0D, RoadPlannerSegmentType.ROAD)
        );
        List<com.monpai.sailboatmod.road.model.BuildStep> steps = RoadSurfaceStepEmitter.emit(
                sparseTurn,
                List.of(new RoadSpan(RoadSpanType.ROAD, 0, 2, RoadPlannerSegmentType.ROAD)),
                RoadPlannerBuildSettings.DEFAULTS,
                0
        );

        List<BlockPos> surface = steps.stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertTrue(surface.contains(new BlockPos(3, 64, 1)), surface.toString());
        assertTrue(surface.contains(new BlockPos(4, 64, 2)), surface.toString());
    }

    @Test
    void roadFootprintDoesNotBleedIntoAdjacentBridgeSpan() {
        List<RoadCenterlinePoint> mixedCenterline = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.ROAD),
                point(4, 64, 0, 4.0D, RoadPlannerSegmentType.ROAD),
                point(8, 64, 0, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );
        List<com.monpai.sailboatmod.road.model.BuildStep> steps = RoadSurfaceStepEmitter.emit(
                mixedCenterline,
                List.of(
                        new RoadSpan(RoadSpanType.ROAD, 0, 1, RoadPlannerSegmentType.ROAD),
                        new RoadSpan(RoadSpanType.BRIDGE, 2, 2, RoadPlannerSegmentType.BRIDGE_MAJOR)
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                0
        );

        List<BlockPos> surface = steps.stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertTrue(surface.contains(new BlockPos(4, 64, 0)), surface.toString());
        assertFalse(surface.contains(new BlockPos(5, 64, 0)),
                "road surface should stop at the road span and not sweep into the bridge span");
    }

    @Test
    void smoothedSteepRoadEmitsRampSteps() {
        RoadTerrainSampler steep = (x, z) -> 64 + x * 2;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 88, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                steep,
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.state().isAir()));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION && !step.state().isAir()));
    }


    @Test
    void bridgeSpanEmitsRampDeckPierAndRailingPhases() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING));
    }

    @Test
    void bridgeDeckEmitsOverheadClearanceLikeRoadSurface() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        List<BlockPos> bridgeSurface = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                        || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();
        List<BlockPos> airClearance = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION)
                .filter(step -> step.state().isAir())
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertFalse(bridgeSurface.isEmpty());
        assertTrue(bridgeSurface.stream().anyMatch(pos -> airClearance.contains(pos.above())),
                "bridge ramp/deck footprint should clear at least one block above the surface");
    }

    @Test
    void ordinaryLongBridgeIsLowerThanOldWaterPlusFiveDeck() {
        RoadTerrainSampler waterSampler = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };

        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                waterSampler,
                RoadStructureMode.BUILD
        );

        int maxDeckY = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .mapToInt(BlockPos::getY)
                .max()
                .orElseThrow();

        assertTrue(maxDeckY <= 66, "ordinary long bridge should be lower than the previous 68-block deck");
    }

    @Test
    void bridgeRampDeckFootprintsRemainConnectedAcrossTurn() {
        List<RoadCenterlinePoint> sparseTurn = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 4, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );
        List<com.monpai.sailboatmod.road.model.BuildStep> steps = BridgeStructureEmitter.emit(
                sparseTurn,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, 2, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                RoadTerrainSampler.flat(61)
        );

        List<BlockPos> bridgeSurface = steps.stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                        || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertTrue(bridgeSurface.stream().anyMatch(pos -> pos.getX() == 3 && pos.getZ() == 1), bridgeSurface.toString());
        assertTrue(bridgeSurface.stream().anyMatch(pos -> pos.getX() == 4 && pos.getZ() == 2), bridgeSurface.toString());
    }

    @Test
    void northSouthBridgeDeckAndRailingsUseCrossPathWidth() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(0, 64, 24)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        List<BlockPos> deck = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();
        List<BlockPos> railings = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        int deckY = deck.stream()
                .filter(pos -> pos.getZ() == 12 && pos.getX() == 0)
                .mapToInt(BlockPos::getY)
                .max()
                .orElseThrow();
        assertTrue(deck.contains(new BlockPos(-2, deckY, 12)), "bridge deck width should extend west/east");
        assertTrue(deck.contains(new BlockPos(2, deckY, 12)), "bridge deck width should extend west/east");
        assertTrue(railings.contains(new BlockPos(-3, deckY + 1, 12)), "left railing should sit outside west edge");
        assertTrue(railings.contains(new BlockPos(3, deckY + 1, 12)), "right railing should sit outside east edge");
    }

    @Test
    void shortSmallBridgeSkipsPiersLikeActualBridgeBuilder() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(62),
                RoadStructureMode.BUILD
        );

        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertFalse(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER));
    }

    @Test
    void bridgeRampProfileDoesNotJumpMoreThanOneBlockPerSample() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        assertBridgeCenterlineDoesNotJumpMoreThanOneBlock(result);
    }

    private static void assertBridgeCenterlineDoesNotJumpMoreThanOneBlock(RoadNodeExpansionResult result) {
        List<BlockPos> centerRampAndDeck = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                        || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .filter(pos -> pos.getZ() == 0)
                .sorted(java.util.Comparator.<BlockPos>comparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY))
                .toList();

        int previousX = Integer.MIN_VALUE;
        int previousY = Integer.MIN_VALUE;
        for (BlockPos pos : centerRampAndDeck) {
            if (pos.getX() == previousX) {
                continue;
            }
            if (previousX != Integer.MIN_VALUE) {
                assertTrue(Math.abs(pos.getY() - previousY) <= 1, "bridge y jump at x=" + pos.getX());
            }
            previousX = pos.getX();
            previousY = pos.getY();
        }
    }

    @Test
    void longBridgePiersUseOceanFloorAndActualDeckHeight() {
        RoadTerrainSampler waterSampler = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 63;
            }

            @Override
            public int waterSurfaceY(int x, int z) {
                return 63;
            }

            @Override
            public int oceanFloorY(int x, int z) {
                return 54;
            }
        };
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 63, 0), new BlockPos(48, 63, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                waterSampler,
                RoadStructureMode.BUILD
        );

        List<BlockPos> piers = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertFalse(piers.isEmpty());
        assertEquals(54, piers.stream().mapToInt(BlockPos::getY).min().orElseThrow());
        assertTrue(piers.stream().mapToInt(BlockPos::getY).max().orElseThrow() <= 66);
    }

    void blockedBridgeMarkerBuildsAsMajorBridge() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0)),
                List.of(RoadPlannerSegmentType.BLOCKED_REQUIRES_BRIDGE),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        assertTrue(result.canonicalSegmentTypes().contains(RoadPlannerSegmentType.BRIDGE_MAJOR));
        assertTrue(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK));
        assertFalse(result.buildSteps().stream().anyMatch(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.SURFACE));
    }

    private static RoadCenterlinePoint point(int x, int y, int z, double distance, RoadPlannerSegmentType segmentType) {
        return new RoadCenterlinePoint(
                new BlockPos(x, y, z),
                0,
                segmentType,
                y,
                y,
                distance
        );
    }
}
