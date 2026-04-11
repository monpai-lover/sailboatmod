package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualRoadPlannerServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void duplicateManualRoadOnSameTownPairIsRejected() {
        assertTrue(ManualRoadPlannerService.manualRoadAlreadyExistsForTest(
                "manual|town:alpha|town:beta",
                Set.of("manual|town:alpha|town:beta")
        ));
    }

    @Test
    void plannerModeCyclesBuildCancelDemolish() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.STICK);

        assertEquals("BUILD", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
        ManualRoadPlannerService.cyclePlannerModeForTest(stack);
        assertEquals("CANCEL", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
        ManualRoadPlannerService.cyclePlannerModeForTest(stack);
        assertEquals("DEMOLISH", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
    }

    @Test
    void manualRoadIdForTownPairUsesStableEdgeKey() {
        assertEquals(
                "manual|town:alpha|town:beta",
                ManualRoadPlannerService.manualRoadIdForTest("alpha", "beta")
        );
        assertEquals(
                "manual|town:alpha|town:beta",
                ManualRoadPlannerService.manualRoadIdForTest("beta", "alpha")
        );
    }

    @Test
    void cyclingPlannerModeClearsPendingPreviewConfirmationState() {
        ItemStack stack = new ItemStack(net.minecraft.world.item.Items.STICK);
        stack.getOrCreateTag().putString("PreviewRoadId", "manual|town:a|town:b");
        stack.getOrCreateTag().putString("PreviewHash", "preview-hash");

        ManualRoadPlannerService.cyclePlannerModeForTest(stack);

        assertFalse(stack.getOrCreateTag().contains("PreviewRoadId"));
        assertFalse(stack.getOrCreateTag().contains("PreviewHash"));
        assertEquals("CANCEL", ManualRoadPlannerService.readPlannerModeForTest(stack).name());
    }

    @Test
    void strictManualPlanningRejectsFallbackWhenStationPairIsMissing() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(false, true, false, false);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.SOURCE_STATION_MISSING, failure);
    }

    @Test
    void strictManualPlanningRejectsMissingTargetExit() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, false);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.TARGET_EXIT_MISSING, failure);
    }

    @Test
    void strictManualPlanningAllowsOnlyFullyResolvedWaitingAreaRoute() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateStrictPostStationRoute(true, true, true, true);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.NONE, failure);
    }

    @Test
    void waitingAreaRouteValidationDoesNotRequireExitsBeforeTheyAreResolved() {
        ManualRoadPlannerService.ManualPlanFailure failure =
                ManualRoadPlannerService.validateWaitingAreaRouteStationsForTest(true, true);

        assertEquals(ManualRoadPlannerService.ManualPlanFailure.NONE, failure);
    }

    @Test
    void townAnchorFallbackIsAllowedWhenAStationIsMissing() {
        assertTrue(ManualRoadPlannerService.shouldAttemptTownAnchorFallbackForTest(
                ManualRoadPlannerService.ManualPlanFailure.SOURCE_STATION_MISSING
        ));
        assertTrue(ManualRoadPlannerService.shouldAttemptTownAnchorFallbackForTest(
                ManualRoadPlannerService.ManualPlanFailure.TARGET_STATION_MISSING
        ));
    }

    @Test
    void townAnchorFallbackIsNotUsedForSuccessfulWaitingAreaResolution() {
        assertFalse(ManualRoadPlannerService.shouldAttemptTownAnchorFallbackForTest(
                ManualRoadPlannerService.ManualPlanFailure.NONE
        ));
    }

    @Test
    void unblocksChosenStationWaitingAreaAndExitColumns() {
        Set<Long> blocked = ManualRoadPlannerService.unblockStationFootprint(
                Set.of(ManualRoadPlannerService.columnKeyForTest(100, 100),
                        ManualRoadPlannerService.columnKeyForTest(101, 100),
                        ManualRoadPlannerService.columnKeyForTest(102, 100)),
                Set.of(new BlockPos(100, 64, 100), new BlockPos(101, 64, 100)),
                new BlockPos(102, 64, 100)
        );

        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(100, 100)));
        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(101, 100)));
        assertFalse(blocked.contains(ManualRoadPlannerService.columnKeyForTest(102, 100)));
    }

    @Test
    void roadSurfaceColumnsCountAsReusableAnchors() {
        assertTrue(ManualRoadPlannerService.isRoadAnchorColumnForTest(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void solidNonRoadOccupantsStillInvalidateAnchors() {
        assertFalse(ManualRoadPlannerService.isRoadAnchorColumnForTest(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.COBBLESTONE.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void waterFallbackIsDisabledWhenLandPassSucceeds() {
        assertFalse(ManualRoadPlannerService.shouldUseWaterFallbackForTest(true, true));
    }

    @Test
    void waterFallbackActivatesOnlyAfterLandPassFails() {
        assertTrue(ManualRoadPlannerService.shouldUseWaterFallbackForTest(false, true));
    }

    @Test
    void plannerFallsBackToWaterOnlyWhenLandRouteIsUnavailable() {
        ManualRoadPlannerService.RouteAttemptDecision decision =
                ManualRoadPlannerService.routeAttemptDecisionForTest(false, true);

        assertTrue(decision.usedWaterFallback());
    }

    @Test
    void manualRoadPreviewIncludesFullBridgeApproachSupportsAndLights() {
        RoadPlacementPlan plan = bridgePreviewPlanFixture(validCorridorPlanFixture(true));

        SyncRoadPlannerPreviewPacket packet = ManualRoadPlannerService.previewPacketForTest(
                "SourceTown",
                "TargetTown",
                plan,
                true
        );

        assertNotNull(packet);
        assertTrue(packet.ghostBlocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(2, 70, 0))));
        assertTrue(packet.ghostBlocks().stream().anyMatch(block -> block.pos().equals(new BlockPos(1, 61, 0))));
    }

    @Test
    void manualRoadPreviewFailsWhenBuildStepsOrCorridorPlanAreInvalid() {
        assertNull(ManualRoadPlannerService.previewPacketForTest(
                "SourceTown",
                "TargetTown",
                bridgePreviewPlanFixture(validCorridorPlanFixture(true), List.of()),
                true
        ));
        assertNull(ManualRoadPlannerService.previewPacketForTest(
                "SourceTown",
                "TargetTown",
                bridgePreviewPlanFixture(null),
                true
        ));
        assertNull(ManualRoadPlannerService.previewPacketForTest(
                "SourceTown",
                "TargetTown",
                bridgePreviewPlanFixture(validCorridorPlanFixture(false)),
                true
        ));
    }

    private static RoadPlacementPlan bridgePreviewPlanFixture(RoadCorridorPlan corridorPlan) {
        return bridgePreviewPlanFixture(corridorPlan, bridgeBuildStepsFixture());
    }

    private static RoadPlacementPlan bridgePreviewPlanFixture(RoadCorridorPlan corridorPlan,
                                                              java.util.List<RoadGeometryPlanner.RoadBuildStep> buildSteps) {
        java.util.List<BlockPos> centerPath = java.util.List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );
        java.util.List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = java.util.List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 66, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(2, 70, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 62, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 61, 0), Blocks.LANTERN.defaultBlockState())
        );
        return new RoadPlacementPlan(
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                centerPath.get(centerPath.size() - 1),
                ghostBlocks,
                buildSteps,
                java.util.List.of(new RoadPlacementPlan.BridgeRange(1, 1)),
                java.util.List.of(new RoadPlacementPlan.BridgeRange(1, 1)),
                ghostBlocks.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList(),
                centerPath.get(0).above(),
                centerPath.get(centerPath.size() - 1).above(),
                new BlockPos(1, 66, 0),
                corridorPlan
        );
    }

    private static java.util.List<RoadGeometryPlanner.RoadBuildStep> bridgeBuildStepsFixture() {
        return java.util.List.of(
                new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(1, 66, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(2, 70, 0), Blocks.STONE_BRICK_STAIRS.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(3, new BlockPos(1, 62, 0), Blocks.STONE_BRICKS.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(4, new BlockPos(1, 61, 0), Blocks.LANTERN.defaultBlockState())
        );
    }

    private static RoadCorridorPlan validCorridorPlanFixture(boolean valid) {
        return new RoadCorridorPlan(
                java.util.List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                java.util.List.of(
                        new RoadCorridorPlan.CorridorSlice(
                                0,
                                new BlockPos(0, 65, 0),
                                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                java.util.List.of(new BlockPos(0, 65, 0)),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                1,
                                new BlockPos(1, 66, 0),
                                RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN,
                                java.util.List.of(new BlockPos(1, 66, 0)),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(new BlockPos(1, 62, 0)),
                                java.util.List.of(new BlockPos(1, 61, 0))
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                2,
                                new BlockPos(2, 70, 0),
                                RoadCorridorPlan.SegmentKind.LAND_APPROACH,
                                java.util.List.of(new BlockPos(2, 70, 0)),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of()
                        )
                ),
                new RoadCorridorPlan.NavigationChannel(new BlockPos(1, 61, 0), new BlockPos(1, 65, 0)),
                valid
        );
    }
}
