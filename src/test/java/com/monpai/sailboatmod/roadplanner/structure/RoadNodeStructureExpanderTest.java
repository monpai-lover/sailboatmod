package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
        assertEquals("路径节点不足，至少需要两个有效节点。", result.issues().get(0).message());
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
    void roadAcrossShallowPitKeepsGradeAndFillsDepression() {
        RoadTerrainSampler shallowPit = (x, z) -> x >= 8 && x <= 11 ? 60 : 64;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                shallowPit,
                RoadStructureMode.BUILD
        );

        List<RoadCenterlinePoint> pitCenterline = result.centerline().stream()
                .filter(point -> point.pos().getX() >= 8 && point.pos().getX() <= 11)
                .toList();
        assertFalse(pitCenterline.isEmpty());
        assertTrue(pitCenterline.stream().allMatch(point -> point.targetY() == 64),
                "road tool should fill shallow ground pits instead of lowering the road grade: " + pitCenterline);

        List<BuildStep> pitSurfaces = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> step.pos().getX() >= 8 && step.pos().getX() <= 11)
                .filter(step -> step.pos().getZ() == 0)
                .toList();
        assertFalse(pitSurfaces.isEmpty());
        assertTrue(pitSurfaces.stream().allMatch(step -> step.pos().getY() == 63),
                "surface blocks over shallow pits should stay at the surrounding road height: " + pitSurfaces);
        assertTrue(result.buildSteps().stream()
                        .anyMatch(step -> step.phase() == BuildPhase.FOUNDATION
                                && !step.state().isAir()
                                && step.pos().getX() >= 8
                                && step.pos().getX() <= 11
                                && step.pos().getZ() == 0
                                && step.pos().getY() < 63),
                "shallow pit should be handled by foundation fill below the road surface");
    }

    @Test
    void roadAcrossNarrowDeepHoleKeepsGradeInsteadOfDivingIntoTunnel() {
        RoadTerrainSampler narrowHole = (x, z) -> x >= 8 && x <= 10 ? 56 : 64;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(24, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                narrowHole,
                RoadStructureMode.BUILD
        );

        List<RoadCenterlinePoint> holeCenterline = result.centerline().stream()
                .filter(point -> point.pos().getX() >= 8 && point.pos().getX() <= 10)
                .toList();
        assertFalse(holeCenterline.isEmpty());
        assertTrue(holeCenterline.stream().allMatch(point -> point.targetY() == 64),
                "normal road tool should fill narrow holes instead of smoothing the grade down into them: " + holeCenterline);

        List<BuildStep> holeSurfaces = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> step.pos().getX() >= 8 && step.pos().getX() <= 10)
                .filter(step -> step.pos().getZ() == 0)
                .toList();
        assertFalse(holeSurfaces.isEmpty());
        assertTrue(holeSurfaces.stream().allMatch(step -> step.pos().getY() == 63),
                "road surface over a narrow hole should remain at surrounding ground height: " + holeSurfaces);
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

        assertTrue(surface.contains(new BlockPos(-2, 63, 0)), "width should extend west of a north-south road");
        assertTrue(surface.contains(new BlockPos(2, 63, 0)), "width should extend east of a north-south road");
        assertFalse(surface.contains(new BlockPos(0, 63, -2)), "width must not extend backwards along the road direction");
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

        assertTrue(surface.contains(new BlockPos(3, 63, 1)), surface.toString());
        assertTrue(surface.contains(new BlockPos(4, 63, 2)), surface.toString());
    }

    @Test
    void roadStreetlightsArePlacedOutsideRoadSurfaceColumnsAtTurns() {
        List<RoadCenterlinePoint> turn = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.ROAD),
                point(4, 64, 0, 4.0D, RoadPlannerSegmentType.ROAD),
                point(4, 64, 4, 8.0D, RoadPlannerSegmentType.ROAD)
        );
        List<BuildStep> steps = RoadSurfaceStepEmitter.emit(
                turn,
                List.of(new RoadSpan(RoadSpanType.ROAD, 0, 2, RoadPlannerSegmentType.ROAD)),
                RoadPlannerBuildSettings.DEFAULTS,
                0
        );

        Set<Long> roadColumns = steps.stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .map(step -> columnKey(step.pos()))
                .collect(java.util.stream.Collectors.toSet());
        List<BuildStep> lampPosts = steps.stream()
                .filter(step -> step.phase() == BuildPhase.STREETLIGHT)
                .filter(step -> step.state().is(Blocks.OAK_FENCE))
                .toList();

        assertFalse(lampPosts.isEmpty(), "turning road should still emit streetlight posts");
        assertTrue(lampPosts.stream().noneMatch(step -> roadColumns.contains(columnKey(step.pos()))),
                "streetlight posts must be pushed outside the rasterized road surface columns: " + lampPosts);
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

        assertTrue(surface.contains(new BlockPos(4, 63, 0)), surface.toString());
        assertFalse(surface.contains(new BlockPos(5, 63, 0)),
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
    void roadCrestRampSlabsUseLocalSlopeDirection() {
        List<RoadCenterlinePoint> crest = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.ROAD),
                point(1, 65, 0, 1.0D, RoadPlannerSegmentType.ROAD),
                point(2, 64, 0, 2.0D, RoadPlannerSegmentType.ROAD)
        );

        List<BuildStep> centerRamp = RoadSurfaceStepEmitter.emit(
                        crest,
                        List.of(new RoadSpan(RoadSpanType.ROAD, 0, 2, RoadPlannerSegmentType.ROAD)),
                        RoadPlannerBuildSettings.DEFAULTS,
                        0
                ).stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() >= 0 && step.pos().getX() <= 2)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertEquals(3, centerRamp.size(), centerRamp.toString());
        assertEquals(SlabType.TOP, centerRamp.get(0).state().getValue(SlabBlock.TYPE),
                "the lower block before an uphill step must use a top slab");
        assertEquals(SlabType.BOTTOM, centerRamp.get(1).state().getValue(SlabBlock.TYPE),
                "the higher crest block must use a bottom slab before the downhill side");
        assertEquals(SlabType.TOP, centerRamp.get(2).state().getValue(SlabBlock.TYPE),
                "the lower block after a downhill step must use a top slab");
    }

    @Test
    void roadRampSlabsReplaceTerrainSurfaceInsteadOfFloatingAboveIt() {
        List<RoadCenterlinePoint> slope = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.ROAD),
                point(1, 65, 0, 1.0D, RoadPlannerSegmentType.ROAD),
                point(2, 65, 0, 2.0D, RoadPlannerSegmentType.ROAD)
        );

        List<BuildStep> centerRamp = RoadSurfaceStepEmitter.emit(
                        slope,
                        List.of(new RoadSpan(RoadSpanType.ROAD, 0, 2, RoadPlannerSegmentType.ROAD)),
                        RoadPlannerBuildSettings.DEFAULTS,
                        0
                ).stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() >= 0 && step.pos().getX() <= 2)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertTrue(centerRamp.stream().anyMatch(step ->
                        step.pos().equals(new BlockPos(0, 63, 0))
                                && step.state().getValue(SlabBlock.TYPE) == SlabType.TOP),
                "the lower ramp approach must replace the terrain top block with a top slab");
        assertTrue(centerRamp.stream().anyMatch(step ->
                        step.pos().equals(new BlockPos(1, 64, 0))
                                && step.state().getValue(SlabBlock.TYPE) == SlabType.BOTTOM),
                "the uphill half-step must sit in the next terrain block space, not float one block higher");
        assertFalse(centerRamp.stream().anyMatch(step -> step.pos().equals(new BlockPos(0, 64, 0))),
                "road ramp slabs must not be placed in the air above the lower terrain surface");
        assertFalse(centerRamp.stream().anyMatch(step -> step.pos().equals(new BlockPos(1, 65, 0))),
                "road ramp slabs must not be shifted one full block above the uphill terrain surface");
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
    void bridgeDeckEmitsStreetlightsAtIntervals() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        List<BuildStep> streetlights = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.STREETLIGHT)
                .toList();
        long deckColumns = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .map(step -> step.pos().getX() + ":" + step.pos().getZ())
                .distinct()
                .count();

        assertFalse(streetlights.isEmpty(), "bridge railings should receive interval streetlights");
        assertTrue(streetlights.size() < deckColumns, "streetlights should be spaced out instead of replacing every railing");
        assertTrue(result.previewBlocks().stream().anyMatch(block -> block.phase() == BuildPhase.STREETLIGHT));
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
        List<RoadCenterlinePoint> denseTurn = List.of(
                point(0, 64, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 64, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 64, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 64, 0, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 1, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 2, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 3, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 64, 4, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );
        List<com.monpai.sailboatmod.road.model.BuildStep> steps = BridgeStructureEmitter.emit(
                denseTurn,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, denseTurn.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
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
    void lowArchBridgeRampSlabsHaveUnderfillAtTerrainContact() {
        RoadTerrainSampler narrowWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 64;
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
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS,
                narrowWater,
                RoadStructureMode.BUILD
        );

        List<BuildStep> rampSlabs = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .toList();
        List<BuildStep> underfill = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.FOUNDATION || step.phase() == BuildPhase.PIER)
                .filter(step -> !step.state().isAir())
                .toList();

        assertFalse(rampSlabs.isEmpty());
        assertTrue(rampSlabs.stream().allMatch(ramp -> underfill.stream().anyMatch(support ->
                        support.pos().getX() == ramp.pos().getX()
                                && support.pos().getZ() == ramp.pos().getZ()
                                && support.pos().getY() == ramp.pos().getY() - 1
                                && support.order() < ramp.order())),
                "short arch bridge ramp slabs must be backed by an underfill block so the approach touches terrain/water");
    }

    @Test
    void bridgeRampStartsAtLegacyPlatformHeight() {
        RoadTerrainSampler shoreAndWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return 64;
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
                List.of(new BlockPos(0, 64, 0), new BlockPos(12, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_SMALL),
                RoadPlannerBuildSettings.DEFAULTS,
                shoreAndWater,
                RoadStructureMode.BUILD
        );

        BuildStep firstCenterRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .min(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();

        assertEquals(64, firstCenterRamp.pos().getY(),
                "bridge ramp should start at the legacy platform height instead of being globally raised");
        assertEquals(SlabType.BOTTOM, firstCenterRamp.state().getValue(SlabBlock.TYPE),
                "ascending bridge ramp should still start with a bottom slab at the platform height");
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

    @Test
    void shortBridgeWithHighShoreStillEmitsContinuousLegacyRampSteps() {
        RoadTerrainSampler highExitShore = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return x >= 8 ? 70 : 63;
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
                List.of(new BlockPos(0, 63, 0), new BlockPos(8, 70, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                highExitShore,
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

    @Test
    void bridgePiersStopBelowDeckToAvoidDuplicateConstructionPositions() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        List<BlockPos> deckPositions = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();
        List<BlockPos> pierPositions = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER)
                .map(com.monpai.sailboatmod.road.model.BuildStep::pos)
                .toList();

        assertTrue(pierPositions.stream().noneMatch(deckPositions::contains),
                "pier blocks must stop below deck blocks because construction progress tracks positions by BlockPos");
    }

    @Test
    void bridgePiersAreBuiltBeforeDeckInSameColumn() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 64, 0), new BlockPos(48, 64, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        List<com.monpai.sailboatmod.road.model.BuildStep> piers = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER)
                .toList();
        List<com.monpai.sailboatmod.road.model.BuildStep> deck = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.DECK)
                .toList();

        assertTrue(piers.stream().anyMatch(pier -> deck.stream().anyMatch(surface ->
                        surface.pos().getX() == pier.pos().getX()
                                && surface.pos().getZ() == pier.pos().getZ()
                                && surface.pos().getY() > pier.pos().getY()
                                && pier.order() < surface.order())),
                "at least one supported deck column should have its pier emitted first");
    }

    @Test
    void bridgeRampFootprintIsSupportedBeforeRampBlocksOverWater() {
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

        List<com.monpai.sailboatmod.road.model.BuildStep> rampBlocks = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .toList();
        List<com.monpai.sailboatmod.road.model.BuildStep> supports = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.FOUNDATION
                        || step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.PIER)
                .filter(step -> !step.state().isAir())
                .toList();

        assertFalse(rampBlocks.isEmpty());
        assertTrue(rampBlocks.stream().allMatch(ramp -> supports.stream().anyMatch(support ->
                        support.pos().getX() == ramp.pos().getX()
                                && support.pos().getZ() == ramp.pos().getZ()
                                && support.pos().getY() < ramp.pos().getY()
                                && support.order() < ramp.order())),
                "every bridge ramp footprint block over water needs a lower support before the ramp is placed");
    }

    @Test
    void bridgeRampSupportsAreFoundationSoPreviewKeepsSlopeSurfaceClean() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 63, 0), new BlockPos(48, 63, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> rampBlocks = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .toList();
        List<BuildStep> visibleRampPiers = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.PIER)
                .filter(step -> rampBlocks.stream().anyMatch(ramp ->
                        ramp.pos().getX() == step.pos().getX()
                                && ramp.pos().getZ() == step.pos().getZ()
                                && step.pos().getY() < ramp.pos().getY()))
                .toList();
        List<BuildStep> hiddenRampSupports = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.FOUNDATION)
                .filter(step -> !step.state().isAir())
                .filter(step -> rampBlocks.stream().anyMatch(ramp ->
                        ramp.pos().getX() == step.pos().getX()
                                && ramp.pos().getZ() == step.pos().getZ()
                                && step.pos().getY() < ramp.pos().getY()
                                && step.order() < ramp.order()))
                .toList();

        assertFalse(rampBlocks.isEmpty());
        assertTrue(visibleRampPiers.isEmpty(), "ramp backing should not show as pier markers in the preview");
        assertFalse(hiddenRampSupports.isEmpty(), "ramp backing still needs hidden build support below the slope");
    }

    @Test
    void ascendingBridgeRampFootprintKeepsEachLegacyStepAtOneHeight() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 63, 0), new BlockPos(48, 63, 24)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> rampSlabs = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .sorted(Comparator.comparingInt(BuildStep::order))
                .toList();

        assertFalse(rampSlabs.isEmpty());
        SlabType currentType = rampSlabs.get(0).state().getValue(SlabBlock.TYPE);
        int currentMinY = rampSlabs.get(0).pos().getY();
        int currentMaxY = currentMinY;
        for (int index = 1; index < rampSlabs.size(); index++) {
            BuildStep step = rampSlabs.get(index);
            SlabType type = step.state().getValue(SlabBlock.TYPE);
            if (type != currentType) {
                assertEquals(currentMinY, currentMaxY,
                        "one legacy half-step footprint must not contain both the old and next Y levels");
                currentType = type;
                currentMinY = step.pos().getY();
                currentMaxY = currentMinY;
                continue;
            }
            currentMinY = Math.min(currentMinY, step.pos().getY());
            currentMaxY = Math.max(currentMaxY, step.pos().getY());
        }
        assertEquals(currentMinY, currentMaxY,
                "one legacy half-step footprint must not contain both the old and next Y levels");
    }

    @Test
    void bridgeRampSlabsFollowLegacyAscendingAndDescendingPairs() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 63, 0), new BlockPos(48, 63, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertEquals(12, centerRamp.size(), "three-block bridge rise should emit six legacy slab steps per approach");

        int[] ascendingY = {63, 63, 64, 64, 65, 65};
        SlabType[] ascendingSlabs = {
                SlabType.BOTTOM, SlabType.TOP,
                SlabType.BOTTOM, SlabType.TOP,
                SlabType.BOTTOM, SlabType.TOP
        };
        for (int i = 0; i < ascendingY.length; i++) {
            BuildStep step = centerRamp.get(i);
            assertEquals(ascendingY[i], step.pos().getY(),
                    "ascending ramp should only move up after each bottom/top pair at index " + i);
            assertEquals(ascendingSlabs[i], step.state().getValue(SlabBlock.TYPE),
                    "ascending ramp should use the legacy bottom/top alternation at index " + i);
        }

        int[] descendingY = {65, 65, 64, 64, 63, 63};
        SlabType[] descendingSlabs = {
                SlabType.TOP, SlabType.BOTTOM,
                SlabType.TOP, SlabType.BOTTOM,
                SlabType.TOP, SlabType.BOTTOM
        };
        for (int i = 0; i < descendingY.length; i++) {
            BuildStep step = centerRamp.get(centerRamp.size() - descendingY.length + i);
            assertEquals(descendingY[i], step.pos().getY(),
                    "descending ramp should move down before each top/bottom pair at index " + i);
            assertEquals(descendingSlabs[i], step.state().getValue(SlabBlock.TYPE),
                    "descending ramp should use the legacy top/bottom alternation at index " + i);
        }
    }

    @Test
    void ascendingBridgeEntryRampKeepsHalfStepBetweenFirstTwoRampSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(-20, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(48, 63, 0),
                        new BlockPos(68, 63, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerEntryRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() < 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();
        assertTrue(centerEntryRamp.size() >= 2, centerEntryRamp.toString());

        BuildStep firstRamp = centerEntryRamp.get(0);
        BuildStep secondRamp = centerEntryRamp.get(1);
        BuildStep previousRoadSurface = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() < firstRamp.pos().getX())
                .max(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();
        assertEquals(surfaceTopHalfUnits(previousRoadSurface) + 1, surfaceTopHalfUnits(firstRamp),
                "the first uphill ramp step must start one half-step above the road surface instead of flattening with it: previousRoad="
                        + previousRoadSurface + ", entryRamp=" + centerEntryRamp);
        assertEquals(surfaceTopHalfUnits(firstRamp) + 1, surfaceTopHalfUnits(secondRamp),
                "the first two uphill ramp steps must be separated by one half-step: entryRamp="
                        + centerEntryRamp);
    }

    @Test
    void descendingBridgeExitRampKeepsFinalHalfStepBetweenPenultimateAndRoadSurface() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(-20, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(48, 63, 0),
                        new BlockPos(68, 63, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerExitRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() > 48)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();
        assertTrue(centerExitRamp.size() >= 2, centerExitRamp.toString());

        BuildStep penultimateRamp = centerExitRamp.get(centerExitRamp.size() - 2);
        BuildStep finalRamp = centerExitRamp.get(centerExitRamp.size() - 1);
        BuildStep nextRoadSurface = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() > finalRamp.pos().getX())
                .min(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();

        assertEquals(surfaceTopHalfUnits(penultimateRamp) - 1, surfaceTopHalfUnits(finalRamp),
                "the last descending ramp step must be one half-step below the penultimate step: exitRamp="
                        + centerExitRamp);
        assertEquals(surfaceTopHalfUnits(nextRoadSurface) + 1, surfaceTopHalfUnits(finalRamp),
                "the last descending ramp step must be one half-step above the road surface instead of flattening with the penultimate ramp: finalRamp="
                        + finalRamp + ", nextRoad=" + nextRoadSurface + ", exitRamp=" + centerExitRamp);
    }

    @Test
    void bridgeDeckBoundaryUsesBottomSlabToCompleteRampHalfStep() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(0, 63, 0), new BlockPos(48, 63, 0)),
                List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerDeck = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertFalse(centerDeck.isEmpty());
        BuildStep firstDeck = centerDeck.get(0);
        BuildStep lastDeck = centerDeck.get(centerDeck.size() - 1);

        assertTrue(firstDeck.state().hasProperty(SlabBlock.TYPE),
                "the first bridge deck block after the ascending ramp must be a half slab, not a full block");
        assertEquals(SlabType.BOTTOM, firstDeck.state().getValue(SlabBlock.TYPE));
        assertTrue(lastDeck.state().hasProperty(SlabBlock.TYPE),
                "the last bridge deck block before the descending ramp must be a half slab, not a full block");
        assertEquals(SlabType.BOTTOM, lastDeck.state().getValue(SlabBlock.TYPE));
    }

    @Test
    void bridgeRampConsumesRoadApproachSoUphillStartsAtRoadSurface() {
        RoadTerrainSampler approachHigherThanWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return x <= 0 ? 66 : 63;
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
                List.of(new BlockPos(0, 66, -4), new BlockPos(0, 66, 0), new BlockPos(8, 63, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                approachHigherThanWater,
                RoadStructureMode.BUILD
        );

        int minBridgeHeadSurfaceY = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE
                        || step.phase() == BuildPhase.RAMP
                        || step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() == 1)
                .mapToInt(step -> step.pos().getY())
                .min()
                .orElseThrow();

        assertEquals(66, minBridgeHeadSurfaceY,
                "bridge transition should start at the adjacent road surface height instead of sinking below it");
    }

    @Test
    void bridgeTransitionColumnsDoNotKeepSeparateRoadSurfaceBelowOrAboveRamp() {
        RoadTerrainSampler shoreAndWater = new RoadTerrainSampler() {
            @Override
            public int terrainY(int x, int z) {
                return x <= 0 ? 66 : 63;
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
                List.of(new BlockPos(0, 66, -4), new BlockPos(0, 66, 0), new BlockPos(8, 63, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                shoreAndWater,
                RoadStructureMode.BUILD
        );

        List<BuildStep> usableAtBridgeHead = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE
                        || step.phase() == BuildPhase.RAMP
                        || step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() == 1 && step.pos().getZ() == 0)
                .toList();

        assertEquals(1, usableAtBridgeHead.size(),
                "road and bridge must not leave two usable surfaces in the same transition X/Z column");
        assertTrue(usableAtBridgeHead.get(0).phase() == BuildPhase.RAMP
                        || usableAtBridgeHead.get(0).phase() == BuildPhase.DECK,
                "the remaining transition surface should belong to the bridge");
    }

    @Test
    void bridgeTransitionKeepsLegacyHalfStepRampContinuityWhenExtraRoadSamplesFit() {
        RoadTerrainSampler flatWater = new RoadTerrainSampler() {
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
                List.of(
                        new BlockPos(-2, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(8, 63, 0),
                        new BlockPos(10, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                flatWater,
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertFalse(centerRamp.isEmpty());
        for (int index = 1; index < centerRamp.size(); index++) {
            BuildStep previous = centerRamp.get(index - 1);
            BuildStep current = centerRamp.get(index);
            assertTrue(Math.abs(current.pos().getY() - previous.pos().getY()) <= 1,
                    "bridge ramp Y must remain continuous at " + previous.pos() + " -> " + current.pos());
        }
        assertTrue(centerRamp.stream().anyMatch(step -> step.state().getValue(SlabBlock.TYPE) == SlabType.BOTTOM));
        assertTrue(centerRamp.stream().anyMatch(step -> step.state().getValue(SlabBlock.TYPE) == SlabType.TOP));
    }

    @Test
    void bridgeRampTransitionDoesNotDipBelowAdjacentRoadSurface() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(-2, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(8, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR, RoadPlannerSegmentType.ROAD),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        BuildStep lastRoadSurface = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() == -2 && step.pos().getZ() == 0)
                .findFirst()
                .orElseThrow();
        BuildStep firstBridgeRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .min(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();

        assertTrue(surfaceTopHalfUnits(firstBridgeRamp) >= surfaceTopHalfUnits(lastRoadSurface),
                "bridge transition ramp must not create a half-block dip from road into bridge: road="
                        + lastRoadSurface + ", bridge=" + firstBridgeRamp);

        BuildStep nextRoadSurface = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.SURFACE)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() == 12 && step.pos().getZ() == 0)
                .findFirst()
                .orElseThrow();
        BuildStep lastBridgeRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .max(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();

        assertTrue(surfaceTopHalfUnits(lastBridgeRamp) >= surfaceTopHalfUnits(nextRoadSurface),
                "bridge exit ramp must not leave a half-block dip before the road surface: bridge="
                        + lastBridgeRamp + ", road=" + nextRoadSurface);
    }

    @Test
    void turnedBridgeRampKeepsFullWidthHalfStepSurfaceAfterRoadTransition() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BlockPos> firstRisingColumns = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getX() == 1)
                .map(BuildStep::pos)
                .map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()))
                .distinct()
                .sorted(Comparator.comparingInt(BlockPos::getZ))
                .toList()
                ;

        assertEquals(List.of(
                        new BlockPos(1, 0, -2),
                        new BlockPos(1, 0, -1),
                        new BlockPos(1, 0, 0),
                        new BlockPos(1, 0, 1),
                        new BlockPos(1, 0, 2)
                ),
                firstRisingColumns,
                "bridge ramp turn must keep the full X/Z width even when RoadWeaver projection assigns different half-step heights");
    }

    @Test
    void turnedBridgeRampCompletionDoesNotAddOutsideTurnWideningNoise() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BlockPos> outsideBridgeRun = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .map(BuildStep::pos)
                .filter(pos -> pos.getX() >= 1 && Math.abs(pos.getZ()) > RoadPlannerBuildSettings.DEFAULTS.width() / 2)
                .sorted(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                        .thenComparingInt(pos -> pos.getY())
                        .thenComparingInt(pos -> pos.getZ()))
                .toList();

        assertEquals(List.of(), outsideBridgeRun,
                "ramp half-slab completion must not add turn-widening cells outside the bridge run width");
    }

    @Test
    void turnedBridgeAscendingRampDoesNotDropAfterClimbingHalfSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        for (int z = -RoadPlannerBuildSettings.DEFAULTS.width() / 2; z <= RoadPlannerBuildSettings.DEFAULTS.width() / 2; z++) {
            int previousHalfUnits = Integer.MIN_VALUE;
            for (int x = 0; x <= 4; x++) {
                int currentX = x;
                int currentZ = z;
                List<BuildStep> column = result.buildSteps().stream()
                        .filter(step -> step.phase() == BuildPhase.RAMP)
                        .filter(step -> !step.state().isAir())
                        .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                        .filter(step -> step.pos().getX() == currentX && step.pos().getZ() == currentZ)
                        .sorted(Comparator.comparingInt(RoadNodeStructureExpanderTest::surfaceTopHalfUnits))
                        .toList();
                if (column.isEmpty()) {
                    continue;
                }
                int topHalfUnits = surfaceTopHalfUnits(column.get(column.size() - 1));
                assertTrue(topHalfUnits >= previousHalfUnits,
                        "ascending bridge ramp must grow forward without dropping at x=" + x + ", z=" + z
                                + ", previous=" + previousHalfUnits + ", current=" + topHalfUnits
                                + ", column=" + column);
                previousHalfUnits = topHalfUnits;
            }
        }
    }

    @Test
    void turnedBridgeAscendingRampHasContinuousHalfStepRowsInEveryLane() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        for (int z = -RoadPlannerBuildSettings.DEFAULTS.width() / 2; z <= RoadPlannerBuildSettings.DEFAULTS.width() / 2; z++) {
            int previousHalfUnits = Integer.MIN_VALUE;
            for (int x = 1; x <= 6; x++) {
                int currentX = x;
                int currentZ = z;
                BuildStep ramp = result.buildSteps().stream()
                        .filter(step -> step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK)
                        .filter(step -> !step.state().isAir())
                        .filter(step -> step.pos().getX() == currentX && step.pos().getZ() == currentZ)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("missing bridge surface at x=" + currentX + ", z=" + currentZ
                                + ", nearby=" + result.buildSteps().stream()
                                .filter(step -> (step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK)
                                        && step.pos().getX() >= currentX - 1 && step.pos().getX() <= currentX + 1
                                        && step.pos().getZ() >= currentZ - 1 && step.pos().getZ() <= currentZ + 1)
                                .map(step -> step.phase() + "@" + step.pos() + ":" + step.state())
                                .toList()));
                int topHalfUnits = surfaceTopHalfUnits(ramp);
                if (previousHalfUnits != Integer.MIN_VALUE) {
                    int delta = topHalfUnits - previousHalfUnits;
                    assertTrue(delta >= 0 && delta <= 2,
                            "ascending bridge ramp must not create a multi-block cliff at x=" + x + ", z=" + z
                                    + ", previous=" + previousHalfUnits + ", current=" + topHalfUnits);
                }
                previousHalfUnits = topHalfUnits;
            }
        }
    }

    @Test
    void bridgeRoadTransitionRampReachesBottomSlabBeforeFullDeckSurface() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BuildStep> centerDeck = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertTrue(centerDeck.size() >= 4, centerDeck.toString());
        BuildStep firstDeck = centerDeck.get(0);
        assertFalse(firstDeck.state().hasProperty(SlabBlock.TYPE),
                "when road transition samples are consumed by the uphill ramp, the first deck cell should already be full surface: decks="
                        + centerDeck + ", ramps=" + result.buildSteps().stream()
                        .filter(step -> step.phase() == BuildPhase.RAMP)
                        .filter(step -> !step.state().isAir())
                        .filter(step -> step.pos().getZ() == 0)
                        .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                                .thenComparingInt(BuildStep::order))
                        .toList());
        BuildStep lastAscendingRamp = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getZ() == 0)
                .filter(step -> step.pos().getX() < firstDeck.pos().getX())
                .max(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .orElseThrow();
        assertEquals(SlabType.BOTTOM, lastAscendingRamp.state().getValue(SlabBlock.TYPE),
                "the last uphill step before full deck must be the bottom slab half-step");
        assertEquals(surfaceTopHalfUnits(firstDeck) - 1, surfaceTopHalfUnits(lastAscendingRamp),
                "the last uphill bottom slab should sit exactly one half-step below the full deck");
        assertFalse(centerDeck.get(1).state().hasProperty(SlabBlock.TYPE),
                "second deck cell after an ascending ramp should already be full deck surface");

        BuildStep beforeExitRamp = centerDeck.get(centerDeck.size() - 2);
        BuildStep lastBeforeExitRamp = centerDeck.get(centerDeck.size() - 1);
        assertFalse(beforeExitRamp.state().hasProperty(SlabBlock.TYPE),
                "second-to-last deck cell before a descending ramp should remain full deck surface");
        assertTrue(lastBeforeExitRamp.state().hasProperty(SlabBlock.TYPE),
                "last deck cell before a descending ramp must be a bottom slab");
        assertEquals(SlabType.BOTTOM, lastBeforeExitRamp.state().getValue(SlabBlock.TYPE));
    }

    @Test
    void turnedBridgeRampDoesNotStackMultipleVisibleSlabsInOneColumn() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 63, -4),
                        new BlockPos(0, 63, 0),
                        new BlockPos(12, 63, 0)
                ),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<BlockPos> stackedColumns = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .collect(java.util.stream.Collectors.groupingBy(
                        step -> new BlockPos(step.pos().getX(), 0, step.pos().getZ()),
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.counting()
                ))
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() > 1)
                .map(java.util.Map.Entry::getKey)
                .sorted(Comparator.comparingInt((BlockPos pos) -> pos.getX())
                        .thenComparingInt(pos -> pos.getZ()))
                .toList();

        assertEquals(List.of(), stackedColumns,
                "a bridge ramp surface column must have one visible slab, not stacked legacy bucket leftovers");
    }

    @Test
    void diagonalBridgeRampUsesCardinalWidthInsteadOfDiagonalExpansion() {
        List<RoadCenterlinePoint> diagonalBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 1, 1.4D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 2, 2.8D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 63, 3, 4.2D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 4, 5.6D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(5, 63, 5, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(6, 63, 6, 8.4D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(7, 63, 7, 9.8D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 8, 11.2D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                diagonalBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, diagonalBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        List<BlockPos> firstRampSliceColumns = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .map(BuildStep::pos)
                .filter(pos -> pos.getX() == 0 && pos.getZ() >= -2 && pos.getZ() <= 2)
                .map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()))
                .distinct()
                .sorted(Comparator.comparingInt(BlockPos::getZ))
                .toList();

        assertEquals(List.of(
                        new BlockPos(0, 0, -2),
                        new BlockPos(0, 0, -1),
                        new BlockPos(0, 0, 0),
                        new BlockPos(0, 0, 1),
                        new BlockPos(0, 0, 2)
                ),
                firstRampSliceColumns,
                "bridge ramp width must expand on a cardinal axis, not diagonally across X/Z");
    }

    @Test
    void straightDiagonalBridgeRampCoversTheSameSurfaceBandAsRoadWeaverSegments() {
        List<RoadCenterlinePoint> diagonalBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 1, 1.4D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 2, 2.8D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 63, 3, 4.2D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 4, 5.6D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(5, 63, 5, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(6, 63, 6, 8.4D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(7, 63, 7, 9.8D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 8, 11.2D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(9, 63, 9, 12.6D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(10, 63, 10, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(11, 63, 11, 15.4D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(12, 63, 12, 16.8D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(13, 63, 13, 18.2D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(14, 63, 14, 19.6D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(15, 63, 15, 21.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(16, 63, 16, 22.4D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                diagonalBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, diagonalBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        Set<BlockPos> expectedSurfaceBand = new HashSet<>();
        for (List<BlockPos> bucket : RoadBandRasterizer.surfacePositionsByIndex(diagonalBridge, RoadPlannerBuildSettings.DEFAULTS.width())) {
            for (BlockPos pos : bucket) {
                expectedSurfaceBand.add(new BlockPos(pos.getX(), 0, pos.getZ()));
            }
        }
        Set<BlockPos> actualBridgeSurface = new HashSet<>();
        for (BuildStep step : steps) {
            if ((step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) && !step.state().isAir()) {
                actualBridgeSurface.add(new BlockPos(step.pos().getX(), 0, step.pos().getZ()));
            }
        }

        expectedSurfaceBand.removeAll(actualBridgeSurface);

        assertTrue(expectedSurfaceBand.isEmpty(),
                "straight diagonal bridge ramp/deck should cover every RoadWeaver-style road-band cell without holes: missing="
                        + expectedSurfaceBand);
    }

    @Test
    void turnedBridgeRampCoversTheSameSurfaceBandAsRoadWeaverSegments() {
        List<RoadCenterlinePoint> turningBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 63, 0, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 1, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 2, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 3, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 4, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(5, 63, 4, 9.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(6, 63, 4, 10.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(7, 63, 4, 11.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 4, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(9, 63, 4, 13.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(10, 63, 4, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(11, 63, 4, 15.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(12, 63, 4, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                turningBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, turningBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        Set<BlockPos> expectedSurfaceBand = new HashSet<>();
        for (List<BlockPos> bucket : RoadBandRasterizer.surfacePositionsByIndex(turningBridge, RoadPlannerBuildSettings.DEFAULTS.width())) {
            for (BlockPos pos : bucket) {
                expectedSurfaceBand.add(new BlockPos(pos.getX(), 0, pos.getZ()));
            }
        }
        Set<BlockPos> actualBridgeSurface = new HashSet<>();
        for (BuildStep step : steps) {
            if ((step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) && !step.state().isAir()) {
                actualBridgeSurface.add(new BlockPos(step.pos().getX(), 0, step.pos().getZ()));
            }
        }

        expectedSurfaceBand.removeAll(actualBridgeSurface);

        assertTrue(expectedSurfaceBand.isEmpty(),
                "turned bridge ramp/deck should cover every RoadWeaver-style road-band cell without holes: missing="
                        + expectedSurfaceBand);
    }

    @Test
    void turnedBridgeRampSurfaceBandDoesNotCreateAdjacentHeightCliffs() {
        List<RoadCenterlinePoint> turningBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 63, 0, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 1, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 2, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 3, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 4, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(5, 63, 4, 9.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(6, 63, 4, 10.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(7, 63, 4, 11.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 4, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(9, 63, 4, 13.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(10, 63, 4, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(11, 63, 4, 15.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(12, 63, 4, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                turningBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, turningBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        java.util.Map<BlockPos, BuildStep> surfaceByColumn = new java.util.HashMap<>();
        for (BuildStep step : steps) {
            if ((step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) && !step.state().isAir()) {
                surfaceByColumn.put(new BlockPos(step.pos().getX(), 0, step.pos().getZ()), step);
            }
        }

        java.util.List<String> cliffs = new java.util.ArrayList<>();
        for (java.util.Map.Entry<BlockPos, BuildStep> entry : surfaceByColumn.entrySet()) {
            BlockPos pos = entry.getKey();
            for (BlockPos neighbor : List.of(pos.east(), pos.south())) {
                BlockPos neighborColumn = new BlockPos(neighbor.getX(), 0, neighbor.getZ());
                BuildStep neighborStep = surfaceByColumn.get(neighborColumn);
                if (neighborStep != null && Math.abs(surfaceTopHalfUnits(neighborStep) - surfaceTopHalfUnits(entry.getValue())) > 1) {
                    cliffs.add(pos + "=" + surfaceTopHalfUnits(entry.getValue()) + ":" + entry.getValue().phase()
                            + "@" + entry.getValue().pos() + " -> "
                            + neighborColumn + "=" + surfaceTopHalfUnits(neighborStep) + ":" + neighborStep.phase()
                            + "@" + neighborStep.pos());
                }
            }
        }

        assertTrue(cliffs.isEmpty(),
                "turned bridge ramp should not create adjacent half-step cliffs in the visible surface band: " + cliffs);
    }

    @Test
    void bridgeRampSwitchesWidthAxisAfterTurnInsideRampRun() {
        List<RoadCenterlinePoint> turningBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 1, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 2, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 3, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 4, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 5, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 6, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 7, 9.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 8, 10.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 9, 11.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 10, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 11, 13.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 12, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 13, 15.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 14, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                turningBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, turningBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        List<BlockPos> firstNorthSouthRampSliceColumns = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .map(BuildStep::pos)
                .filter(pos -> pos.getZ() == 1)
                .map(pos -> new BlockPos(pos.getX(), 0, pos.getZ()))
                .distinct()
                .sorted(Comparator.comparingInt(BlockPos::getX))
                .toList();

        assertEquals(List.of(
                        new BlockPos(0, 0, 1),
                        new BlockPos(1, 0, 1),
                        new BlockPos(2, 0, 1),
                        new BlockPos(3, 0, 1),
                        new BlockPos(4, 0, 1)
                ),
                firstNorthSouthRampSliceColumns,
                "after the bridge ramp turns north/south, the next ramp slice must switch width to east/west");
    }

    @Test
    void turnedBridgeRampLateralCellsFollowProjectedHalfStepHeight() {
        List<RoadCenterlinePoint> turningBridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 1, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 2, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 3, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 4, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 5, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 6, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 7, 9.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 8, 10.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 9, 11.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 10, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 11, 13.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 12, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 13, 15.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 14, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                turningBridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, turningBridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        BuildStep farLaneAtTurn = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getX() == 0 && step.pos().getZ() == 1)
                .findFirst()
                .orElseThrow();
        BuildStep centerLaneAtTurn = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getX() == 2 && step.pos().getZ() == 1)
                .findFirst()
                .orElseThrow();

        assertEquals(127, surfaceTopHalfUnits(farLaneAtTurn),
                "turn-side ramp cells should keep their projected half-step instead of being lifted into a cliff");
        assertTrue(surfaceTopHalfUnits(centerLaneAtTurn) >= surfaceTopHalfUnits(farLaneAtTurn)
                        && surfaceTopHalfUnits(centerLaneAtTurn) <= 130,
                "the ramp turn should climb toward the centerline without forcing every lateral cell to the highest step");
    }

    @Test
    void descendingBridgeRampKeepsHigherHalfStepWhenFootprintsOverlap() {
        List<RoadCenterlinePoint> bridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(1, 63, 0, 1.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(2, 63, 0, 2.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(3, 63, 0, 3.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(5, 63, 0, 5.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(6, 63, 0, 6.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(7, 63, 0, 7.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 0, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(9, 63, 0, 9.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(10, 63, 0, 10.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(11, 63, 0, 11.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(12, 63, 0, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(13, 63, 0, 13.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(14, 63, 0, 14.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(15, 63, 0, 15.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(15, 63, 1, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                bridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, bridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        BuildStep overlapColumn = steps.stream()
                .filter(step -> step.phase() == BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.state().hasProperty(SlabBlock.TYPE))
                .filter(step -> step.pos().getX() == 14 && step.pos().getZ() == 0)
                .findFirst()
                .orElseThrow();

        assertEquals(new BlockPos(14, 64, 0), overlapColumn.pos(),
                "descending ramp overlap must keep the higher half-step instead of the later lower slab");
        assertEquals(SlabType.BOTTOM, overlapColumn.state().getValue(SlabBlock.TYPE));
    }

    @Test
    void buildStepsUseUniquePositionsForPersistedRoadJobs() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(12, 64, 0),
                        new BlockPos(36, 64, 0),
                        new BlockPos(48, 64, 12)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                RoadTerrainSampler.flat(60),
                RoadStructureMode.BUILD
        );

        java.util.Map<BlockPos, List<com.monpai.sailboatmod.road.model.BuildStep>> byPos = result.buildSteps().stream()
                .collect(java.util.stream.Collectors.groupingBy(
                        com.monpai.sailboatmod.road.model.BuildStep::pos,
                        java.util.LinkedHashMap::new,
                        java.util.stream.Collectors.toList()
                ));
        List<String> duplicates = byPos.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(entry -> entry.getKey() + " -> " + entry.getValue().stream()
                        .map(step -> step.phase() + ":" + step.state().getBlock())
                        .toList())
                .toList();

        assertTrue(duplicates.isEmpty(),
                "road construction persistence validates build steps by BlockPos only: " + duplicates);
        List<BuildStep> nearbyRamps = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() >= 34 && step.pos().getX() <= 38 && step.pos().getZ() >= 0 && step.pos().getZ() <= 4)
                .toList();
        assertFalse(nearbyRamps.isEmpty(),
                "bridge ramp must win over overlapping clearance air: " + nearbyRamps);
        List<BuildStep> nearbyRailings = result.buildSteps().stream()
                .filter(step -> step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getX() >= 34 && step.pos().getX() <= 38 && step.pos().getZ() >= 1 && step.pos().getZ() <= 5)
                .toList();
        assertFalse(nearbyRailings.isEmpty(),
                "bridge railing must win over overlapping clearance air: " + nearbyRailings);
    }

    @Test
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

    @Test
    void ascendingBridgeRampUsesRoadTransitionSamplesAsHalfSteps() {
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(new BlockPos(-8, 63, 0), new BlockPos(0, 63, 0), new BlockPos(40, 63, 0)),
                List.of(RoadPlannerSegmentType.ROAD, RoadPlannerSegmentType.BRIDGE_MAJOR),
                RoadPlannerBuildSettings.DEFAULTS,
                deepWaterSampler(),
                RoadStructureMode.BUILD
        );

        List<Integer> ascendingSurfaceHalfUnits = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(BuildStep::order))
                .map(RoadNodeStructureExpanderTest::surfaceTopHalfUnits)
                .toList();

        assertTrue(ascendingSurfaceHalfUnits.size() >= 8, ascendingSurfaceHalfUnits.toString());
        assertEquals(List.of(127, 128, 129, 130, 131, 132, 133, 134),
                ascendingSurfaceHalfUnits.subList(0, 8),
                "road transition samples before an uphill bridge must continue the half-step climb instead of repeating road height");
    }

    @Test
    void bridgeDeckFootprintFillsBetweenSparseDeckCenterSamples() {
        List<RoadCenterlinePoint> bridge = List.of(
                point(0, 63, 0, 0.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 0, 4.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(4, 63, 4, 8.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(8, 63, 4, 12.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(12, 63, 4, 16.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(16, 63, 4, 20.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(20, 63, 4, 24.0D, RoadPlannerSegmentType.BRIDGE_MAJOR),
                point(24, 63, 4, 28.0D, RoadPlannerSegmentType.BRIDGE_MAJOR)
        );

        List<BuildStep> steps = BridgeStructureEmitter.emit(
                bridge,
                List.of(new RoadSpan(RoadSpanType.BRIDGE, 0, bridge.size() - 1, RoadPlannerSegmentType.BRIDGE_MAJOR)),
                RoadPlannerBuildSettings.DEFAULTS,
                BridgeTemplateProvider.empty(),
                0,
                deepWaterSampler()
        );

        Set<BlockPos> visibleSurfaces = new HashSet<>();
        for (BuildStep step : steps) {
            if ((step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK) && !step.state().isAir()) {
                visibleSurfaces.add(new BlockPos(step.pos().getX(), 0, step.pos().getZ()));
            }
        }

        assertTrue(visibleSurfaces.contains(new BlockPos(10, 0, 3)),
                "bridge deck footprint must rasterize the swept surface between center samples instead of only isolated cross-sections: " + visibleSurfaces);
        assertTrue(visibleSurfaces.contains(new BlockPos(10, 0, 5)),
                "bridge deck footprint must keep the full lane width between deck center samples: " + visibleSurfaces);
    }

    @Test
    void bridgePreviewRampDoesNotSkipHalfStepLevelsWhenApproachRiseNeedsMoreSamples() {
        assertBridgePreviewCenterlineHalfStepsAreContinuous(16, 9);
    }

    @Test
    void bridgePreviewRampKeepsHalfStepContinuityAcrossShortRiseMatrix() {
        for (int bridgeLength : List.of(4, 6, 8, 12, 16, 20)) {
            for (int rise : List.of(1, 2, 3, 4, 6, 8, 10)) {
                assertBridgePreviewCenterlineHalfStepsAreContinuous(bridgeLength, rise);
            }
        }
    }

    private static void assertBridgePreviewCenterlineHalfStepsAreContinuous(int bridgeLength, int rise) {
        int highY = 63 + rise;
        RoadNodeExpansionResult result = RoadNodeStructureExpander.expand(
                List.of(
                        new BlockPos(-20, 63, 0),
                        new BlockPos(0, 63, 0),
                        new BlockPos(bridgeLength, highY, 0),
                        new BlockPos(bridgeLength + 24, highY, 0)
                ),
                List.of(
                        RoadPlannerSegmentType.ROAD,
                        RoadPlannerSegmentType.BRIDGE_MAJOR,
                        RoadPlannerSegmentType.ROAD
                ),
                RoadPlannerBuildSettings.DEFAULTS,
                (RoadTerrainSampler) null,
                RoadStructureMode.PREVIEW
        );

        List<BuildStep> centerBridgeSurfaces = result.buildSteps().stream()
                .filter(step -> step.phase() == BuildPhase.RAMP || step.phase() == BuildPhase.DECK)
                .filter(step -> !step.state().isAir())
                .filter(step -> step.pos().getZ() == 0)
                .sorted(Comparator.comparingInt((BuildStep step) -> step.pos().getX())
                        .thenComparingInt(RoadNodeStructureExpanderTest::surfaceTopHalfUnits)
                        .thenComparingInt(BuildStep::order))
                .toList();

        assertTrue(centerBridgeSurfaces.size() > 1,
                "bridge preview should emit at least two visible centerline surface samples for bridgeLength="
                        + bridgeLength + ", rise=" + rise + ": " + centerBridgeSurfaces);
        for (int index = 1; index < centerBridgeSurfaces.size(); index++) {
            BuildStep previous = centerBridgeSurfaces.get(index - 1);
            BuildStep current = centerBridgeSurfaces.get(index);
            int delta = Math.abs(surfaceTopHalfUnits(current) - surfaceTopHalfUnits(previous));
            assertTrue(delta <= 1,
                    "actual preview bridge ramp must not skip a half-step level between visible samples: previous="
                            + previous + ", current=" + current + ", bridgeLength=" + bridgeLength
                            + ", rise=" + rise + ", center=" + centerBridgeSurfaces);
        }
    }

    private static RoadTerrainSampler deepWaterSampler() {
        return new RoadTerrainSampler() {
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
    }

    private static int surfaceTopHalfUnits(BuildStep step) {
        if (step.state().hasProperty(SlabBlock.TYPE)) {
            return step.pos().getY() * 2 + (step.state().getValue(SlabBlock.TYPE) == SlabType.TOP ? 2 : 1);
        }
        return step.pos().getY() * 2 + 2;
    }

    private static long columnKey(BlockPos pos) {
        return (((long) pos.getX()) << 32) ^ (pos.getZ() & 0xffffffffL);
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
