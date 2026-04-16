package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.network.packet.SyncManualRoadPlanningProgressPacket;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
    void previewRequestReturnsPlanningMessageBeforeAsyncResultArrives() {
        assertEquals(
                "message.sailboatmod.road_planner.planning",
                ManualRoadPlannerService.pendingPreviewMessageKeyForTest()
        );
    }

    @Test
    void planningStagePercentBandsFollowApprovedRanges() {
        assertEquals(8, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.PREPARING,
                100
        ));
        assertEquals(18, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.SAMPLING_TERRAIN,
                50
        ));
        assertEquals(34, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.ANALYZING_ISLAND,
                50
        ));
        assertEquals(51, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.TRYING_LAND,
                50
        ));
        assertEquals(74, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE,
                50
        ));
        assertEquals(93, ManualRoadPlannerService.planningOverallPercentForTest(
                ManualRoadPlannerService.PlanningStage.BUILDING_PREVIEW,
                50
        ));
    }

    @Test
    void planningProgressPacketCarriesStageAndStatus() {
        SyncManualRoadPlanningProgressPacket packet = ManualRoadPlannerService.planningPacketForTest(
                12L,
                "Alpha",
                "Beta",
                ManualRoadPlannerService.PlanningStage.BUILDING_PREVIEW,
                50,
                SyncManualRoadPlanningProgressPacket.Status.SUCCESS
        );

        assertEquals(12L, packet.requestId());
        assertEquals("building_preview", packet.stageKey());
        assertEquals("生成预览", packet.stageLabel());
        assertEquals(93, packet.overallPercent());
        assertEquals(SyncManualRoadPlanningProgressPacket.Status.SUCCESS, packet.status());
    }

    @Test
    void islandTargetsGoStraightToBridgeAttemptStage() {
        assertEquals(
                List.of(ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE),
                ManualRoadPlannerService.planningAttemptStagesForTest(false, true)
        );
    }

    @Test
    void islandProbePolicySkipsLandProbeWhenEitherEndpointIsIslandLike() {
        assertEquals(
                new ManualRoadPlannerService.IslandProbePolicy(true, 0, 10, true),
                ManualRoadPlannerService.islandProbePolicyForTest(true, false)
        );
        assertEquals(
                new ManualRoadPlannerService.IslandProbePolicy(true, 0, 10, true),
                ManualRoadPlannerService.islandProbePolicyForTest(false, true)
        );
    }

    @Test
    void islandProbeStopsWhenDistanceBudgetIsConsumed() {
        assertTrue(ManualRoadPlannerService.shouldAbortIslandLandProbeForTest(
                new ManualRoadPlannerService.IslandProbePolicy(true, 1, 10, true),
                10,
                false
        ));
    }

    @Test
    void islandProbeStopsWhenWaterSignalReturns() {
        assertTrue(ManualRoadPlannerService.shouldAbortIslandLandProbeForTest(
                new ManualRoadPlannerService.IslandProbePolicy(true, 1, 10, true),
                3,
                true
        ));
    }

    @Test
    void mainlandTargetsStillTryLandBeforeBridge() {
        assertEquals(
                List.of(
                        ManualRoadPlannerService.PlanningStage.TRYING_LAND,
                        ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE
                ),
                ManualRoadPlannerService.planningAttemptStagesForTest(false, false)
        );
    }

    @Test
    void islandEndpointsSkipLandAttemptStagesAndGoStraightToBridge() {
        assertEquals(
                List.of(ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE),
                ManualRoadPlannerService.planningAttemptStagesForTest(true, false)
        );
        assertEquals(
                List.of(ManualRoadPlannerService.PlanningStage.TRYING_BRIDGE),
                ManualRoadPlannerService.planningAttemptStagesForTest(false, true)
        );
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
    void manualPlannerExcludesColumnsWithinFiveBlocksOfCore() {
        Set<Long> excluded = ManualRoadPlannerService.collectCoreExclusionColumnsForTest(
                List.of(new BlockPos(50, 64, 50)),
                List.of(new BlockPos(90, 64, 90))
        );

        assertTrue(excluded.contains(BlockPos.asLong(45, 0, 50)));
        assertTrue(excluded.contains(BlockPos.asLong(95, 0, 90)));
        assertFalse(excluded.contains(BlockPos.asLong(96, 0, 90)));
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
    void bridgeStairColumnsAlsoCountAsReusableAnchors() {
        assertTrue(ManualRoadPlannerService.isRoadAnchorColumnForTest(
                Blocks.GRASS_BLOCK.defaultBlockState(),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(),
                Blocks.AIR.defaultBlockState()
        ));
    }

    @Test
    void surfaceAtSkipsLeafCanopyAndFindsRealGround() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(0, 0), 70);
        level.blockStates.put(new BlockPos(0, 70, 0).asLong(), Blocks.OAK_LEAVES.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 69, 0).asLong(), Blocks.AIR.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 68, 0).asLong(), Blocks.DIRT.defaultBlockState());

        BlockPos surface = ManualRoadPlannerService.surfaceAtForTest(level, new BlockPos(0, 0, 0));

        assertNotNull(surface);
        assertEquals(new BlockPos(0, 68, 0), surface);
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
    void structuredGroundFailureMapsToLocalizedFailureComponent() {
        assertEquals(
                "message.sailboatmod.road_planner.failure.no_continuous_ground_route",
                ManualRoadPlannerService.manualFailureMessageKeyForTest(
                        RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE
                )
        );
    }

    @Test
    void manualPlannerMapsStructuredSearchExhaustedFailureToLocalizedMessage() {
        assertEquals(
                "message.sailboatmod.road_planner.failure.search_exhausted",
                ManualRoadPlannerService.manualFailureMessageKeyForTest(
                        RoadPlanningFailureReason.SEARCH_EXHAUSTED
                )
        );
    }

    @Test
    void nearestBoundaryAnchorPrefersAccessibleCandidateOverCloserSteepBoundary() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        NationClaimRecord claim = new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town2",
                "member", "member", "member", "member", "member", "member", "member", 0L
        );
        BlockPos toward = new BlockPos(-20, 64, 2);

        for (int z = 1; z <= 14; z++) {
            setSurfaceColumn(level, 1, z, 64, Blocks.WATER.defaultBlockState());
        }

        setSurfaceColumn(level, 1, 2, 80, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 1, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 3, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 0, 2, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 2, 2, 64, Blocks.DIRT.defaultBlockState());

        setSurfaceColumn(level, 1, 10, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 9, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 11, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 0, 10, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 2, 10, 64, Blocks.DIRT.defaultBlockState());

        BlockPos anchor = invokeNearestBoundaryAnchor(level, List.of(claim), toward, 64);

        assertNotNull(anchor);
        assertEquals(1, anchor.getX());
        assertEquals(64, anchor.getY());
        assertFalse(anchor.equals(new BlockPos(1, 80, 2)));
    }

    @Test
    void nearestBoundaryAnchorSkipsCoreExcludedCandidateEvenWhenItIsCloserToTarget() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        NationClaimRecord claim = new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town2",
                "member", "member", "member", "member", "member", "member", "member", 0L
        );
        BlockPos toward = new BlockPos(-20, 64, 2);

        for (int z = 1; z <= 14; z++) {
            setSurfaceColumn(level, 1, z, 64, Blocks.DIRT.defaultBlockState());
        }

        BlockPos excludedCandidate = new BlockPos(1, 64, 2);
        Set<Long> excludedColumns = Set.of(BlockPos.asLong(excludedCandidate.getX(), 0, excludedCandidate.getZ()));

        BlockPos anchor = invokeNearestBoundaryAnchor(level, List.of(claim), toward, 64, excludedColumns);

        assertNotNull(anchor);
        assertEquals(1, anchor.getX());
        assertEquals(64, anchor.getY());
        assertTrue(anchor.getZ() >= 5, anchor.toString());
        assertFalse(excludedCandidate.equals(anchor));
    }

    @Test
    void islandBridgeBoundaryAnchorPrefersShorelineCandidateFacingTarget() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        NationClaimRecord claim = new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town2",
                "member", "member", "member", "member", "member", "member", "member", 0L
        );
        BlockPos toward = new BlockPos(-20, 64, 8);

        for (int x = -2; x <= 18; x++) {
            for (int z = -2; z <= 18; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.DIRT.defaultBlockState());
            }
        }
        for (int z = 6; z <= 10; z++) {
            setSurfaceColumn(level, 0, z, 64, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(0, 63, z).asLong(), Blocks.STONE.defaultBlockState());
        }

        BlockPos anchor = invokeNearestBoundaryAnchor(level, List.of(claim), toward, 64, Set.of(), true);

        assertNotNull(anchor);
        assertEquals(new BlockPos(1, 64, 8), anchor);
    }

    @Test
    void expandedBoundaryAnchorFallsBackOutsideClaimWhenInClaimAnchorsAreUnavailable() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        level.dimensionKey = Level.OVERWORLD;

        NationClaimRecord claim = new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town2",
                "member", "member", "member", "member", "member", "member", "member", 0L
        );

        for (int x = -2; x <= 18; x++) {
            for (int z = -2; z <= 18; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.DIRT.defaultBlockState());
            }
        }
        Set<Long> excludedColumns = new java.util.LinkedHashSet<>();
        for (int x = 0; x <= 15; x++) {
            for (int z = 0; z <= 15; z++) {
                excludedColumns.add(BlockPos.asLong(x, 0, z));
            }
        }

        BlockPos anchor = invokeNearestExtendedBoundaryAnchor(
                level,
                List.of(claim),
                new BlockPos(-20, 64, 8),
                64,
                excludedColumns
        );

        assertEquals(new BlockPos(-2, 64, 8), anchor);
    }

    @Test
    void resolveTownAnchorPrefersOutsideClaimWhenBridgeFootprintWouldClipCoreExclusion() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        level.dimensionKey = Level.OVERWORLD;

        NationClaimRecord claim = new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town2",
                "member", "member", "member", "member", "member", "member", "member", 0L
        );
        for (int x = -2; x <= 18; x++) {
            for (int z = -2; z <= 18; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.DIRT.defaultBlockState());
            }
        }

        Set<Long> excludedColumns = new java.util.LinkedHashSet<>();
        for (int z = 5; z <= 11; z++) {
            excludedColumns.add(BlockPos.asLong(2, 0, z));
        }

        TownRecord town = new TownRecord(
                "town2",
                "nation",
                "Town 2",
                new UUID(0L, 0L),
                0L,
                "",
                TownRecord.noCorePos(),
                "",
                "european"
        );

        BlockPos anchor = invokeResolveTownAnchor(
                level,
                new NationSavedData(),
                town,
                List.of(claim),
                new BlockPos(8, 64, 8),
                new BlockPos(-20, 64, 8),
                excludedColumns,
                false
        );

        assertNotNull(anchor);
        assertTrue(anchor.getX() < 0, anchor.toString());
        assertEquals(64, anchor.getY());
        assertEquals(8, anchor.getZ());
    }

    @Test
    void nearestRoadNodeInClaimsCanReuseExistingRoadNodeInsideCoreExcludedColumn() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        level.dimensionKey = Level.OVERWORLD;

        for (int x = 0; x <= 10; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }
        level.blockStates.put(new BlockPos(5, 65, 0).asLong(), Blocks.STONE_BRICK_SLAB.defaultBlockState());

        NationSavedData data = new NationSavedData();
        data.putRoadNetwork(new RoadNetworkRecord(
                "manual|town:a|town:b",
                "nation",
                "town",
                "minecraft:overworld",
                "town:a",
                "town:b",
                List.of(
                        new BlockPos(4, 64, 0),
                        new BlockPos(5, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                1L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL
        ));
        List<NationClaimRecord> claims = List.of(new NationClaimRecord(
                "minecraft:overworld", 0, 0, "nation", "town",
                "member", "member", "member", "member", "member", "member", "member", 0L
        ));

        BlockPos roadNode = invokeNearestRoadNodeInClaims(
                level,
                data,
                claims,
                new BlockPos(5, 64, 0),
                Set.of(BlockPos.asLong(5, 0, 0))
        );

        assertEquals(new BlockPos(5, 64, 0), roadNode);
    }

    @Test
    void collectSegmentAnchorsKeepsExistingRoadNodesEvenWhenTheyCrossCoreExcludedColumns() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        level.dimensionKey = Level.OVERWORLD;

        for (int x = 0; x <= 12; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }
        level.blockStates.put(new BlockPos(6, 65, 0).asLong(), Blocks.STONE_BRICK_SLAB.defaultBlockState());

        List<BlockPos> anchors = invokeCollectSegmentAnchors(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                Set.of(),
                Set.of(BlockPos.asLong(6, 0, 0)),
                Set.of(new BlockPos(6, 64, 0))
        );

        assertTrue(anchors.contains(new BlockPos(6, 64, 0)));
    }

    @Test
    void plannerFallsBackToWaterOnlyWhenLandRouteIsUnavailable() {
        ManualRoadPlannerService.RouteAttemptDecision decision =
                ManualRoadPlannerService.routeAttemptDecisionForTest(false, true);

        assertTrue(decision.usedWaterFallback());
    }

    @Test
    void bridgeFirstRoutingIsPreferredWhenSourceEndpointIsIslandLike() {
        assertTrue(ManualRoadPlannerService.shouldPreferBridgeFirstForTest(true, false, true));
        assertTrue(ManualRoadPlannerService.shouldPreferBridgeFirstForTest(false, true, true));
        assertFalse(ManualRoadPlannerService.shouldPreferBridgeFirstForTest(false, false, true));
        assertFalse(ManualRoadPlannerService.shouldPreferBridgeFirstForTest(true, false, false));
    }

    @Test
    void limitExitCandidatesCollapsesNearbyEquivalentShorelineExits() {
        List<BlockPos> limited = invokeLimitExitCandidates(
                List.of(
                        new BlockPos(200, 64, 100),
                        new BlockPos(200, 64, 101),
                        new BlockPos(200, 64, 102),
                        new BlockPos(200, 64, 105),
                        new BlockPos(200, 64, 106),
                        new BlockPos(200, 64, 110)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(200, 64, 100),
                        new BlockPos(200, 64, 105),
                        new BlockPos(200, 64, 110)
                ),
                limited
        );
    }

    @Test
    void collectSegmentAnchorsFiltersBlockedIntermediateNodes() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 10; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }
        level.blockStates.put(new BlockPos(6, 65, 0).asLong(), Blocks.COBBLESTONE.defaultBlockState());

        List<BlockPos> anchors = invokeCollectSegmentAnchors(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
                Set.of(),
                Set.of(
                        new BlockPos(3, 64, 0),
                        new BlockPos(6, 64, 0)
                )
        );

        assertEquals(List.of(new BlockPos(3, 64, 0)), anchors);
    }

    @Test
    void collectSegmentAnchorsFiltersCoreExcludedIntermediateNodes() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 10; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }

        List<BlockPos> anchors = invokeCollectSegmentAnchors(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
                Set.of(),
                Set.of(ManualRoadPlannerService.columnKeyForTest(3, 0)),
                Set.of(
                        new BlockPos(3, 64, 0),
                        new BlockPos(6, 64, 0)
                )
        );

        assertEquals(List.of(new BlockPos(6, 64, 0)), anchors);
    }

    @Test
    void blockedBridgeDeckAnchorRemainsEligibleForSegmentSubdivision() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(5, 0), 62);
        level.blockStates.put(new BlockPos(5, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        for (int y = 63; y <= 66; y++) {
            level.blockStates.put(new BlockPos(5, y, 0).asLong(), Blocks.WATER.defaultBlockState());
        }

        List<BlockPos> anchors = invokeFilterTraversableIntermediateAnchors(
                level,
                Set.of(new BlockPos(5, 67, 0)),
                Set.of(columnKey(5, 0)),
                Set.of()
        );

        assertEquals(List.of(new BlockPos(5, 67, 0)), anchors);
    }

    @Test
    void collectSegmentAnchorsDoesNotForceBridgeDeckCandidatesIntoGlobalHybridChain() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 12; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }
        for (int x = 4; x <= 8; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 66);
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
            for (int y = 63; y <= 66; y++) {
                level.blockStates.put(new BlockPos(x, y, 0).asLong(), Blocks.WATER.defaultBlockState());
            }
        }

        List<BlockPos> anchors = invokeCollectSegmentAnchors(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(12, 64, 0),
                Set.of(),
                Set.of(),
                Set.of(),
                true
        );

        assertEquals(List.of(), anchors);
    }

    @Test
    void bridgeEndpointSegmentsBypassHybridNetworkResolver() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        level.surfaceHeights.put(columnKey(5, 0), 62);
        level.blockStates.put(new BlockPos(5, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        for (int y = 63; y <= 66; y++) {
            level.blockStates.put(new BlockPos(5, y, 0).asLong(), Blocks.WATER.defaultBlockState());
        }
        setSurfaceColumn(level, 10, 0, 64, Blocks.DIRT.defaultBlockState());

        assertFalse(invokeShouldUseHybridNetworkForSegment(
                level,
                new BlockPos(5, 67, 0),
                new BlockPos(10, 64, 0),
                true
        ));
    }

    @Test
    void shorelineToShorelineWaterCrossingAlsoBypassesHybridNetworkResolver() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 10, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 2; x <= 8; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        assertFalse(invokeShouldUseHybridNetworkForSegment(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
                true
        ));
    }

    @Test
    void mergedIntermediateAnchorsAreSortedAlongRouteProgress() {
        List<BlockPos> ordered = invokeSortAnchorsAlongRoute(
                new BlockPos(0, 64, 0),
                new BlockPos(10, 64, 0),
                Set.of(
                        new BlockPos(8, 64, 0),
                        new BlockPos(3, 67, 0)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(3, 67, 0),
                        new BlockPos(8, 64, 0)
                ),
                ordered
        );
    }

    @Test
    void normalizePathRejectsDisconnectedDetailedSegments() {
        List<BlockPos> normalized = invokeNormalizePath(
                new BlockPos(0, 64, 0),
                List.of(
                        new BlockPos(1, 64, 0),
                        new BlockPos(9, 64, 0)
                ),
                new BlockPos(10, 64, 0)
        );

        assertTrue(normalized.isEmpty());
    }

    @Test
    void finalizePlannedPathUsesContinuousSnappedRoadSubpath() {
        List<BlockPos> finalized = invokeFinalizePlannedPath(
                List.of(
                        new BlockPos(0, 64, 2),
                        new BlockPos(3, 64, 2),
                        new BlockPos(6, 64, 2)
                ),
                new boolean[] {false, false, false},
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:a|town:b",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:a",
                                "town:b",
                                List.of(
                                        new BlockPos(0, 64, 0),
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(4, 64, 0),
                                        new BlockPos(5, 64, 0),
                                        new BlockPos(6, 64, 0)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                ),
                Set.of()
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0),
                        new BlockPos(5, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                finalized
        );
    }

    @Test
    void finalizePlannedPathRejectsSnappedFallbackThatReentersExcludedColumns() {
        List<BlockPos> finalized = invokeFinalizePlannedPath(
                List.of(
                        new BlockPos(0, 64, 4),
                        new BlockPos(3, 64, 4),
                        new BlockPos(6, 64, 4)
                ),
                new boolean[] {false, false, false},
                List.of(
                        new RoadNetworkRecord(
                                "manual|town:a|town:b",
                                "nation",
                                "town",
                                "minecraft:overworld",
                                "town:a",
                                "town:b",
                                List.of(
                                        new BlockPos(0, 64, 0),
                                        new BlockPos(1, 64, 0),
                                        new BlockPos(2, 64, 0),
                                        new BlockPos(3, 64, 0),
                                        new BlockPos(4, 64, 0),
                                        new BlockPos(5, 64, 0),
                                        new BlockPos(6, 64, 0)
                                ),
                                1L,
                                RoadNetworkRecord.SOURCE_TYPE_MANUAL
                        )
                ),
                Set.of(BlockPos.asLong(3, 0, 0))
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 4),
                        new BlockPos(1, 64, 4),
                        new BlockPos(2, 64, 4),
                        new BlockPos(3, 64, 4),
                        new BlockPos(4, 64, 4),
                        new BlockPos(5, 64, 4),
                        new BlockPos(6, 64, 4)
                ),
                finalized
        );
    }

    @Test
    void finalizePlannedPathReturnsEmptyWhenRoadFootprintTouchesExcludedColumns() {
        List<BlockPos> finalized = invokeFinalizePlannedPath(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 1),
                        new BlockPos(2, 64, 2)
                ),
                new boolean[] {false, false, false},
                List.of(),
                Set.of(
                        BlockPos.asLong(1, 0, 0)
                )
        );

        assertTrue(finalized.isEmpty(), () -> finalized.toString());
    }

    @Test
    void finalizePlannedPathReturnsEmptyWhenEveryCandidateReentersExcludedColumns() {
        List<BlockPos> finalized = invokeFinalizePlannedPath(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(6, 64, 0)
                ),
                new boolean[] {false, false, false},
                List.of(),
                Set.of(
                        BlockPos.asLong(0, 0, 0),
                        BlockPos.asLong(1, 0, 0),
                        BlockPos.asLong(2, 0, 0),
                        BlockPos.asLong(3, 0, 0),
                        BlockPos.asLong(4, 0, 0),
                        BlockPos.asLong(5, 0, 0),
                        BlockPos.asLong(6, 0, 0)
                )
        );

        assertTrue(finalized.isEmpty(), () -> finalized.toString());
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
    void manualRoadPreviewPathNodesUseStructuredCorridorDeckCenters() {
        SyncRoadPlannerPreviewPacket packet = ManualRoadPlannerService.previewPacketForTest(
                "SourceTown",
                "TargetTown",
                bridgePreviewPlanFixture(validCorridorPlanFixture(true)),
                true
        );

        assertNotNull(packet);
        assertEquals(
                List.of(
                        new BlockPos(0, 65, 0),
                        new BlockPos(1, 66, 0),
                        new BlockPos(2, 70, 0)
                ),
                packet.pathNodes()
        );
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

    @Test
    void productionPlacementPlanBuildsContinuousHighReliefRiverCrossing() {
        RoadPlacementPlan plan = buildProductionRiverPlanForTest();

        assertNotNull(plan);
        assertNotNull(plan.corridorPlan());
        assertTrue(plan.corridorPlan().valid());
        assertFalse(plan.buildSteps().isEmpty());
        assertNotNull(ManualRoadPlannerService.previewPacketForTest("A", "B", plan, true));
        assertFalse(plan.bridgeRanges().isEmpty());
        assertTrue(plan.corridorPlan().slices().stream().allMatch(slice -> !slice.surfacePositions().isEmpty()));
        assertTrue(hasProductionBridgeClosure(plan.corridorPlan()), summarizeCorridorPlan(plan.corridorPlan()));
    }

    @Test
    void manualPreviewRejectsProductionPlansWithCorruptedSliceIndexes() {
        RoadPlacementPlan broken = productionRiverPlanWithCorruptedSliceIndexForTest();

        assertNull(ManualRoadPlannerService.previewPacketForTest("A", "B", broken, true));
    }

    @Test
    void stitchRouteSegmentsPreservesSegmentOrderBeyondSharedBoundaries() {
        List<BlockPos> stitched = invokeStitchRouteSegmentsForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                ),
                List.of(
                        new BlockPos(3, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 1)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(0, 64, 0),
                        new BlockPos(0, 64, 1)
                ),
                stitched
        );
    }

    @Test
    void normalizePathRejectsMissingRawRouteInsteadOfFabricatingTwoPointPath() {
        List<BlockPos> normalized = invokeNormalizePathForTest(
                new BlockPos(10, 64, 10),
                List.of(),
                new BlockPos(20, 64, 20)
        );

        assertTrue(normalized.isEmpty());
    }

    @Test
    void selectedHybridPathIsNormalizedAndUsedForPlacementPlanInputs() {
        List<BlockPos> normalized = ManualRoadPlannerService.normalizePathForTest(
                new BlockPos(1, 64, 2),
                List.of(
                        new BlockPos(2, 64, 2),
                        new BlockPos(3, 64, 2),
                        new BlockPos(4, 64, 2),
                        new BlockPos(5, 64, 2),
                        new BlockPos(6, 64, 2)
                ),
                new BlockPos(7, 64, 2)
        );

        assertEquals(
                List.of(
                        new BlockPos(1, 64, 2),
                        new BlockPos(2, 64, 2),
                        new BlockPos(3, 64, 2),
                        new BlockPos(4, 64, 2),
                        new BlockPos(5, 64, 2),
                        new BlockPos(6, 64, 2),
                        new BlockPos(7, 64, 2)
                ),
                normalized
        );
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
                                java.util.List.of(new BlockPos(0, 65, 0), new BlockPos(1, 66, 0)),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of(),
                                java.util.List.of()
                        ),
                        new RoadCorridorPlan.CorridorSlice(
                                1,
                                new BlockPos(1, 66, 0),
                                RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN,
                                java.util.List.of(new BlockPos(1, 66, 0), new BlockPos(2, 70, 0)),
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

    private static RoadPlacementPlan buildProductionRiverPlanForTest() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 63, 0),
                new BlockPos(3, 69, 0),
                new BlockPos(4, 69, 0),
                new BlockPos(5, 71, 0),
                new BlockPos(6, 73, 0),
                new BlockPos(7, 63, 0),
                new BlockPos(8, 63, 0)
        );
        return invokeProductionRoadPlacementPlan(
                highReliefRiverLevelForTest(),
                centerPath,
                centerPath.get(0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                centerPath.get(centerPath.size() - 1)
        );
    }

    private static RoadPlacementPlan productionRiverPlanWithCorruptedSliceIndexForTest() {
        RoadPlacementPlan plan = buildProductionRiverPlanForTest();
        RoadCorridorPlan corridorPlan = plan.corridorPlan();
        assertFalse(corridorPlan.slices().isEmpty());

        List<RoadCorridorPlan.CorridorSlice> brokenSlices = new java.util.ArrayList<>(corridorPlan.slices());
        RoadCorridorPlan.CorridorSlice brokenSlice = corridorPlan.slices().get(0);
        brokenSlices.set(0, new RoadCorridorPlan.CorridorSlice(
                brokenSlice.index() + 1,
                brokenSlice.deckCenter(),
                brokenSlice.segmentKind(),
                brokenSlice.surfacePositions(),
                brokenSlice.excavationPositions(),
                brokenSlice.clearancePositions(),
                brokenSlice.railingLightPositions(),
                brokenSlice.supportPositions(),
                brokenSlice.pierLightPositions()
        ));

        RoadCorridorPlan brokenCorridor = new RoadCorridorPlan(
                corridorPlan.centerPath(),
                brokenSlices,
                corridorPlan.navigationChannel(),
                corridorPlan.valid()
        );
        return new RoadPlacementPlan(
                plan.centerPath(),
                plan.sourceInternalAnchor(),
                plan.sourceBoundaryAnchor(),
                plan.targetBoundaryAnchor(),
                plan.targetInternalAnchor(),
                plan.ghostBlocks(),
                plan.buildSteps(),
                plan.bridgeRanges(),
                plan.navigableWaterBridgeRanges(),
                plan.ownedBlocks(),
                plan.startHighlightPos(),
                plan.endHighlightPos(),
                plan.focusPos(),
                brokenCorridor
        );
    }

    private static RoadPlacementPlan invokeProductionRoadPlacementPlan(ServerLevel level,
                                                                       List<BlockPos> centerPath,
                                                                       BlockPos sourceInternalAnchor,
                                                                       BlockPos sourceBoundaryAnchor,
                                                                       BlockPos targetBoundaryAnchor,
                                                                       BlockPos targetInternalAnchor) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "createRoadPlacementPlan",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    BlockPos.class,
                    BlockPos.class,
                    BlockPos.class
            );
            method.setAccessible(true);
            return (RoadPlacementPlan) method.invoke(
                    null,
                    level,
                    centerPath,
                    sourceInternalAnchor,
                    sourceBoundaryAnchor,
                    targetBoundaryAnchor,
                    targetInternalAnchor
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static ServerLevel highReliefRiverLevelForTest() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 63, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.WATER.defaultBlockState());
        setSurfaceColumn(level, 5, 0, 64, Blocks.WATER.defaultBlockState());
        setSurfaceColumn(level, 7, 0, 63, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 8, 0, 63, Blocks.DIRT.defaultBlockState());
        return level;
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static BlockPos invokeNearestBoundaryAnchor(ServerLevel level,
                                                        List<NationClaimRecord> claims,
                                                        BlockPos towardPos,
                                                        int fallbackY) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "nearestBoundaryAnchor",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    int.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, level, claims, towardPos, fallbackY);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static BlockPos invokeNearestBoundaryAnchor(ServerLevel level,
                                                        List<NationClaimRecord> claims,
                                                        BlockPos towardPos,
                                                        int fallbackY,
                                                        Set<Long> excludedColumns) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "nearestBoundaryAnchor",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    int.class,
                    Set.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, level, claims, towardPos, fallbackY, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static BlockPos invokeNearestExtendedBoundaryAnchor(ServerLevel level,
                                                                List<NationClaimRecord> claims,
                                                                BlockPos towardPos,
                                                                int fallbackY,
                                                                Set<Long> excludedColumns) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "nearestExtendedBoundaryAnchor",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    int.class,
                    Set.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, level, claims, towardPos, fallbackY, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static BlockPos invokeNearestRoadNodeInClaims(ServerLevel level,
                                                          NationSavedData data,
                                                          List<NationClaimRecord> claims,
                                                          BlockPos towardPos,
                                                          Set<Long> excludedColumns) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "nearestRoadNodeInClaims",
                    ServerLevel.class,
                    NationSavedData.class,
                    List.class,
                    BlockPos.class,
                    Set.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, level, data, claims, towardPos, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeCollectSegmentAnchors(ServerLevel level,
                                                              BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<Long> blockedColumns,
                                                              Set<BlockPos> networkNodes) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "collectSegmentAnchors",
                    ServerLevel.class,
                    BlockPos.class,
                    BlockPos.class,
                    Set.class,
                    Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, level, sourceAnchor, targetAnchor, blockedColumns, networkNodes);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean invokeShouldUseHybridNetworkForSegment(ServerLevel level,
                                                                  BlockPos sourceAnchor,
                                                                  BlockPos targetAnchor,
                                                                  boolean allowWaterFallback) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "shouldUseHybridNetworkForSegment",
                    ServerLevel.class,
                    BlockPos.class,
                    BlockPos.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (boolean) method.invoke(null, level, sourceAnchor, targetAnchor, allowWaterFallback);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeCollectSegmentAnchors(ServerLevel level,
                                                              BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<Long> blockedColumns,
                                                              Set<Long> excludedColumns,
                                                              Set<BlockPos> networkNodes) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "collectSegmentAnchors",
                    ServerLevel.class,
                    BlockPos.class,
                    BlockPos.class,
                    Set.class,
                    Set.class,
                    Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, level, sourceAnchor, targetAnchor, blockedColumns, excludedColumns, networkNodes);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeCollectSegmentAnchors(ServerLevel level,
                                                              BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<Long> blockedColumns,
                                                              Set<Long> excludedColumns,
                                                              Set<BlockPos> networkNodes,
                                                              boolean allowWaterFallback) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "collectSegmentAnchors",
                    ServerLevel.class,
                    BlockPos.class,
                    BlockPos.class,
                    Set.class,
                    Set.class,
                    Set.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(
                    null,
                    level,
                    sourceAnchor,
                    targetAnchor,
                    blockedColumns,
                    excludedColumns,
                    networkNodes,
                    allowWaterFallback
            );
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeFilterTraversableIntermediateAnchors(ServerLevel level,
                                                                             Set<BlockPos> anchors,
                                                                             Set<Long> blockedColumns,
                                                                             Set<Long> excludedColumns) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "filterTraversableIntermediateAnchors",
                    ServerLevel.class,
                    Set.class,
                    Set.class,
                    Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, level, anchors, blockedColumns, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeSortAnchorsAlongRoute(BlockPos sourceAnchor,
                                                              BlockPos targetAnchor,
                                                              Set<BlockPos> anchors) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "sortAnchorsAlongRoute",
                    BlockPos.class,
                    BlockPos.class,
                    Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, sourceAnchor, targetAnchor, anchors);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeNormalizePath(BlockPos start,
                                                      List<BlockPos> path,
                                                      BlockPos end) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "normalizePath",
                    BlockPos.class,
                    List.class,
                    BlockPos.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, start, path, end);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static String summarizeCorridorPlan(RoadCorridorPlan corridorPlan) {
        if (corridorPlan == null) {
            return "corridor=null";
        }
        return corridorPlan.slices().stream()
                .map(slice -> slice.index() + ":" + slice.segmentKind() + "@" + slice.deckCenter().getY())
                .toList()
                .toString();
    }

    private static boolean hasProductionBridgeClosure(RoadCorridorPlan corridorPlan) {
        if (corridorPlan == null) {
            return false;
        }
        for (int i = 1; i < corridorPlan.slices().size(); i++) {
            RoadCorridorPlan.CorridorSlice previous = corridorPlan.slices().get(i - 1);
            RoadCorridorPlan.CorridorSlice current = corridorPlan.slices().get(i);
            if ((previous.segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH
                    || current.segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH)
                    && slicesTouchOrShareColumns(previous.surfacePositions(), current.surfacePositions())) {
                return true;
            }
        }
        return false;
    }

    private static boolean slicesTouchOrShareColumns(List<BlockPos> previous, List<BlockPos> current) {
        if (previous == null || current == null) {
            return false;
        }
        for (BlockPos left : previous) {
            for (BlockPos right : current) {
                if (Math.abs(left.getY() - right.getY()) > 2) {
                    continue;
                }
                int horizontalDistance = Math.abs(left.getX() - right.getX()) + Math.abs(left.getZ() - right.getZ());
                if (horizontalDistance <= 1) {
                    return true;
                }
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeStitchRouteSegmentsForTest(List<BlockPos>... segments) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod("stitchRouteSegments", List[].class);
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, new Object[] {segments});
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeNormalizePathForTest(BlockPos start, List<BlockPos> path, BlockPos end) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod("normalizePath", BlockPos.class, List.class, BlockPos.class);
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, start, path, end);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static BlockPos invokeResolveTownAnchor(ServerLevel level,
                                                    NationSavedData data,
                                                    TownRecord town,
                                                    List<NationClaimRecord> claims,
                                                    BlockPos preferredFallback,
                                                    BlockPos towardPos,
                                                    Set<Long> excludedColumns,
                                                    boolean preferShoreline) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "resolveTownAnchor",
                    ServerLevel.class,
                    NationSavedData.class,
                    TownRecord.class,
                    List.class,
                    BlockPos.class,
                    BlockPos.class,
                    Set.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(
                    null,
                    level,
                    data,
                    town,
                    claims,
                    preferredFallback,
                    towardPos,
                    excludedColumns,
                    preferShoreline
            );
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeLimitExitCandidates(List<BlockPos> exits) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod("limitExitCandidates", List.class);
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, exits);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeFinalizePlannedPath(List<BlockPos> path,
                                                            boolean[] bridgeMask,
                                                            List<RoadNetworkRecord> roads,
                                                            Set<Long> excludedColumns) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "finalizePlannedPath",
                    List.class,
                    boolean[].class,
                    List.class,
                    Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, path, bridgeMask, roads, excludedColumns);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static BlockPos invokeNearestBoundaryAnchor(ServerLevel level,
                                                        List<NationClaimRecord> claims,
                                                        BlockPos towardPos,
                                                        int fallbackY,
                                                        Set<Long> excludedColumns,
                                                        boolean preferShoreline) {
        try {
            Method method = ManualRoadPlannerService.class.getDeclaredMethod(
                    "nearestBoundaryAnchor",
                    ServerLevel.class,
                    List.class,
                    BlockPos.class,
                    int.class,
                    Set.class,
                    boolean.class
            );
            method.setAccessible(true);
            return (BlockPos) method.invoke(null, level, claims, towardPos, fallbackY, excludedColumns, preferShoreline);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> surfaceHeights;
        private Holder<Biome> biome;
        private ResourceKey<Level> dimensionKey;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types heightmapType, BlockPos pos) {
            int surfaceY = surfaceHeights.getOrDefault(columnKey(pos.getX(), pos.getZ()), 63);
            return new BlockPos(pos.getX(), surfaceY + 1, pos.getZ());
        }

        @Override
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }

        @Override
        public ResourceKey<Level> dimension() {
            return dimensionKey == null ? Level.OVERWORLD : dimensionKey;
        }
    }
}
