package com.monpai.sailboatmod.roadplanner.structure;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerBuildSettings;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.road.model.BuildPhase;
import com.monpai.sailboatmod.road.model.BuildStep;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Comparator;
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
        assertEquals(BuildPhase.RAMP, usableAtBridgeHead.get(0).phase());
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
        assertTrue(result.buildSteps().stream().anyMatch(step ->
                        step.pos().equals(new BlockPos(36, 63, 2))
                                && step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAMP
                                && !step.state().isAir()),
                "bridge ramp must win over overlapping clearance air");
        assertTrue(result.buildSteps().stream().anyMatch(step ->
                        step.pos().equals(new BlockPos(36, 64, 3))
                                && step.phase() == com.monpai.sailboatmod.road.model.BuildPhase.RAILING
                                && !step.state().isAir()),
                "bridge railing must win over overlapping clearance air");
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
