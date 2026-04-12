package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import com.monpai.sailboatmod.network.packet.SyncRoadPlannerPreviewPacket;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.item.ItemStack;
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
                    && !java.util.Collections.disjoint(previous.surfacePositions(), current.surfacePositions())) {
                return true;
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

    private static final class TestServerLevel extends ServerLevel {
        private Map<Long, BlockState> blockStates;
        private Map<Long, Integer> surfaceHeights;
        private Holder<Biome> biome;

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
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }
    }
}
