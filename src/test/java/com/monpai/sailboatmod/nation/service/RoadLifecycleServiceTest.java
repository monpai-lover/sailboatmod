package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadLifecycleServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rollbackTargetsAllOwnedRoadBlocksInReverseOrder() {
        List<BlockPos> removed = RoadLifecycleService.ownedBlocksRemovalOrderForTest(
                List.of(new BlockPos(1, 65, 1), new BlockPos(1, 64, 1), new BlockPos(1, 63, 1))
        );

        assertEquals(new BlockPos(1, 63, 1), removed.get(0));
        assertTrue(removed.contains(new BlockPos(1, 64, 1)));
        assertEquals(new BlockPos(1, 65, 1), removed.get(removed.size() - 1));
    }

    @Test
    void cancelRejectsWrongLevelWithoutDroppingActiveRoadJob() {
        ServerLevel activeLevel = allocate(ServerLevel.class);
        ServerLevel otherLevel = allocate(ServerLevel.class);
        Object job = newRoadConstructionJob(activeLevel, "manual|town:a|town:b");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put("manual|town:a|town:b", job);
        try {
            assertFalse(invokeCancelActiveRoadConstruction(otherLevel, "manual|town:a|town:b"));
            assertTrue(activeRoads.containsKey("manual|town:a|town:b"));
        } finally {
            restoreMapEntry(activeRoads, "manual|town:a|town:b", previous);
        }
    }

    @Test
    void clearActiveRoadRuntimeStateRemovesWorkersAndQueuedHammerCredits() {
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeWorkers = readStaticMap("ACTIVE_ROAD_WORKERS");
        @SuppressWarnings("unchecked")
        Map<String, Object> activeCredits = readStaticMap("ACTIVE_ROAD_HAMMER_CREDITS");

        Object previousRoad = activeRoads.put("manual|town:a|town:b", newRoadConstructionJob(allocate(ServerLevel.class), "manual|town:a|town:b"));
        Object previousWorkers = activeWorkers.put("manual|town:a|town:b", new ConcurrentHashMap<>());
        Object previousCredits = activeCredits.put("manual|town:a|town:b", 3);
        try {
            invokeClearActiveRoadRuntimeState("manual|town:a|town:b");
            assertFalse(activeRoads.containsKey("manual|town:a|town:b"));
            assertFalse(activeWorkers.containsKey("manual|town:a|town:b"));
            assertFalse(activeCredits.containsKey("manual|town:a|town:b"));
        } finally {
            restoreMapEntry(activeRoads, "manual|town:a|town:b", previousRoad);
            restoreMapEntry(activeWorkers, "manual|town:a|town:b", previousWorkers);
            restoreMapEntry(activeCredits, "manual|town:a|town:b", previousCredits);
        }
    }

    @Test
    void widenedRoadbedTopUsesRoadSurfaceFootprintNotLampPosts() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 72, 0), Blocks.COBBLESTONE_WALL.defaultBlockState())
        );

        List<BlockPos> roadbedTop = invokeRoadbedTopFromFootprint(ghostBlocks);

        assertTrue(roadbedTop.contains(new BlockPos(0, 71, 0)));
        assertTrue(roadbedTop.contains(new BlockPos(1, 71, 0)));
        assertTrue(roadbedTop.contains(new BlockPos(-1, 71, 0)));
        assertEquals(3, roadbedTop.size());
    }

    @Test
    void widenedOwnedBlocksIncludeFootprintAndTerrainEditsForCleanup() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<BlockPos> terrainEdits = List.of(
                new BlockPos(-1, 69, 0),
                new BlockPos(1, 69, 0)
        );

        List<BlockPos> owned = invokeOwnedRoadBlocksFromFootprint(ghostBlocks, terrainEdits);
        List<BlockPos> removalOrder = RoadLifecycleService.ownedBlocksRemovalOrderForTest(owned);

        assertEquals(Set.of(
                new BlockPos(-1, 70, 0),
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0),
                new BlockPos(-1, 69, 0),
                new BlockPos(1, 69, 0)
        ), Set.copyOf(owned));
        assertEquals(owned.get(owned.size() - 1), removalOrder.get(0));
        assertEquals(owned.get(0), removalOrder.get(removalOrder.size() - 1));
    }

    @Test
    void partialPersistedOwnedBlocksFallBackToWidenedFootprintCoverage() {
        RoadPlacementPlan plan = widenedPlanWithPartialOwnedBlocks();

        List<BlockPos> resolved = invokeResolvedRoadOwnedBlocks(null, plan);

        assertEquals(Set.of(
                new BlockPos(-1, 70, 0),
                new BlockPos(0, 70, 0),
                new BlockPos(1, 70, 0)
        ), Set.copyOf(resolved));
    }

    @Test
    void snapshotRoadOwnershipUsesResolvedCoverageForActivePlans() {
        ServerLevel level = allocate(ServerLevel.class);
        RoadPlacementPlan plan = widenedPlanWithPartialOwnedBlocks();
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        String roadId = "manual|town:snapshot_a|town:snapshot_b";
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan));
        try {
            Map<String, List<BlockPos>> snapshot = StructureConstructionManager.snapshotRoadOwnedBlocks(level);
            assertTrue(snapshot.containsKey(roadId));
            assertEquals(Set.of(
                    new BlockPos(-1, 70, 0),
                    new BlockPos(0, 70, 0),
                    new BlockPos(1, 70, 0)
            ), Set.copyOf(snapshot.get(roadId)));
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }

    @Test
    void liveFoundationFallbackCapturesSupportBlocksUnderWidenedRoadColumns() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
        states.put(new BlockPos(-1, 69, 0), Blocks.COBBLESTONE.defaultBlockState());
        states.put(new BlockPos(-1, 68, 0), Blocks.COBBLESTONE.defaultBlockState());
        states.put(new BlockPos(0, 69, 0), Blocks.SANDSTONE.defaultBlockState());
        states.put(new BlockPos(1, 69, 0), Blocks.MUD_BRICKS.defaultBlockState());
        states.put(new BlockPos(1, 68, 0), Blocks.SPRUCE_FENCE.defaultBlockState());
        Function<BlockPos, net.minecraft.world.level.block.state.BlockState> lookup =
                pos -> states.getOrDefault(pos, Blocks.DIRT.defaultBlockState());

        List<BlockPos> captured = invokeCaptureFoundationFromLookup(ghostBlocks, lookup);

        assertEquals(Set.of(
                new BlockPos(-1, 69, 0),
                new BlockPos(-1, 68, 0),
                new BlockPos(0, 69, 0),
                new BlockPos(1, 69, 0),
                new BlockPos(1, 68, 0)
        ), Set.copyOf(captured));
    }

    @Test
    void rollbackTrackedPositionsIncludeHeadspaceAboveRoadDeck() {
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                null,
                null,
                null,
                null,
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())),
                List.of(),
                List.of(),
                List.of(new BlockPos(0, 64, 0)),
                null,
                null,
                null
        );

        List<BlockPos> tracked = invokeRoadRollbackTrackedPositions(null, plan);

        assertEquals(Set.of(
                new BlockPos(0, 64, 0),
                new BlockPos(0, 65, 0)
        ), Set.copyOf(tracked));
    }

    @Test
    void rollbackTrackedPositionsIncludeBridgeSupportsAndLights() {
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                List.of(new BlockPos(0, 68, 0)),
                List.of(new RoadCorridorPlan.CorridorSlice(
                        0,
                        new BlockPos(0, 68, 0),
                        RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN,
                        List.of(new BlockPos(0, 68, 0)),
                        List.of(),
                        List.of(new BlockPos(0, 69, 0)),
                        List.of(new BlockPos(0, 68, 1)),
                        List.of(new BlockPos(0, 67, 0), new BlockPos(0, 66, 0)),
                        List.of(new BlockPos(0, 68, -1))
                )),
                null,
                true
        );
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 68, 0)),
                null,
                null,
                null,
                null,
                List.of(new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 68, 0), Blocks.SPRUCE_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 68, 0), Blocks.SPRUCE_SLAB.defaultBlockState())),
                List.of(),
                List.of(),
                List.of(new BlockPos(0, 68, 0)),
                null,
                null,
                null,
                corridorPlan
        );

        List<BlockPos> tracked = invokeRoadRollbackTrackedPositions(null, plan);

        assertTrue(tracked.contains(new BlockPos(0, 67, 0)));
        assertTrue(tracked.contains(new BlockPos(0, 68, 1)));
        assertTrue(tracked.contains(new BlockPos(0, 68, -1)));
    }

    @Test
    void rollbackTrackedPositionsIncludePlannedPierColumnsFromLongBridgePlan() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 68, 0),
                        new BlockPos(8, 68, 0),
                        new BlockPos(9, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 8)),
                List.of(new RoadPlacementPlan.BridgeRange(4, 5))
        );

        List<BlockPos> tracked = invokeRoadRollbackTrackedPositions(null, plan);
        List<BlockPos> pierBlocks = plan.corridorPlan().slices().stream()
                .flatMap(slice -> slice.supportPositions().stream())
                .toList();

        assertFalse(pierBlocks.isEmpty());
        assertTrue(tracked.containsAll(pierBlocks), () -> tracked.toString());
    }

    @Test
    void rollbackSnapshotsRestoreOriginalTerrainFluidAndClearedHeadspace() {
        List<BlockPos> tracked = List.of(
                new BlockPos(0, 63, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(0, 65, 0)
        );
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();
        states.put(new BlockPos(0, 63, 0), Blocks.SAND.defaultBlockState());
        states.put(new BlockPos(0, 64, 0), Blocks.WATER.defaultBlockState());
        states.put(new BlockPos(0, 65, 0), Blocks.POPPY.defaultBlockState());

        List<Object> snapshots = invokeCaptureRoadRollbackSnapshots(tracked, pos -> states.getOrDefault(pos, Blocks.AIR.defaultBlockState()));

        states.put(new BlockPos(0, 63, 0), Blocks.COBBLESTONE.defaultBlockState());
        states.put(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState());
        states.put(new BlockPos(0, 65, 0), Blocks.AIR.defaultBlockState());

        invokeRestoreRoadRollbackSnapshots(snapshots, states::put);

        assertEquals(Blocks.SAND.defaultBlockState(), states.get(new BlockPos(0, 63, 0)));
        assertEquals(Blocks.WATER.defaultBlockState(), states.get(new BlockPos(0, 64, 0)));
        assertEquals(Blocks.POPPY.defaultBlockState(), states.get(new BlockPos(0, 65, 0)));
    }

    @Test
    void roadPlacementReplaceableStaysOnNaturalTerrainAndDecorations() {
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.STONE.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.SAND.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.GRAVEL.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.GRASS.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.POPPY.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.WATER.defaultBlockState()));
        assertTrue(invokeIsRoadPlacementReplaceable(Blocks.OAK_LOG.defaultBlockState()));

        assertFalse(invokeIsRoadPlacementReplaceable(Blocks.CHEST.defaultBlockState()));
        assertFalse(invokeIsRoadPlacementReplaceable(Blocks.CRAFTING_TABLE.defaultBlockState()));
    }

    @Test
    void landRoadPlacementReplacesSurfaceBlockInsteadOfFloatingAboveIt() {
        StructureConstructionManager.TestRoadPlacementResult result =
                StructureConstructionManager.placeRoadColumnForTest(
                        Blocks.GRASS_BLOCK.defaultBlockState(),
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.AIR.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                );

        assertEquals(Blocks.STONE_BRICK_SLAB, result.surfaceState().getBlock());
        assertEquals(63, result.foundationTopY());
    }

    @Test
    void clearanceEnvelopeRemovesNaturalBlocksAboveRoadButNotPlacedStructures() {
        assertTrue(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.OAK_LEAVES.defaultBlockState()));
        assertTrue(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.GRASS.defaultBlockState()));
        assertFalse(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void placedRoadStepAcceptsWaterloggedSurfaceVariant() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.SPRUCE_SLAB.defaultBlockState()
        );

        assertTrue(invokeIsRoadBuildStepPlaced(
                Blocks.SPRUCE_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true),
                step
        ));
    }

    @Test
    void placedRoadStepRejectsDifferentRoadBlockType() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.SPRUCE_SLAB.defaultBlockState()
        );

        assertFalse(invokeIsRoadBuildStepPlaced(
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(),
                step
        ));
    }

    @Test
    void placedRoadStepAcceptsEquivalentRoadFamilyVariantToPreventRetryLoops() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        assertTrue(invokeIsRoadBuildStepPlaced(
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(),
                step
        ));
    }

    @Test
    void placedRoadStepAcceptsEquivalentStairWithDifferentFacingToPreventRetryLoops() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST)
        );

        assertTrue(invokeIsRoadBuildStepPlaced(
                Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.NORTH),
                step
        ));
    }

    @Test
    void cancelSwitchesActiveRoadJobIntoRollbackModeInsteadOfDeletingImmediately() {
        ServerLevel level = allocate(ServerLevel.class);
        String roadId = "manual|town:cancel_a|town:cancel_b";
        RoadPlacementPlan plan = new RoadPlacementPlan(List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), null, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan, List.of(), 0, 0.0D, false, 0, false));
        try {
            assertTrue(invokeCancelActiveRoadConstruction(level, roadId));
            Object updated = activeRoads.get(roadId);
            assertNotNull(updated);
            assertTrue((boolean) readRecordComponent(updated, "rollbackActive"));
            assertTrue((boolean) readRecordComponent(updated, "removeRoadNetworkOnComplete"));
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }

    @Test
    void incrementalRollbackRestoresTrackedStatesAcrossMultipleBatches() {
        BlockPos firstRoad = new BlockPos(0, 64, 0);
        BlockPos secondRoad = new BlockPos(1, 64, 0);
        BlockPos foundation = new BlockPos(0, 63, 0);
        BlockPos headspace = new BlockPos(1, 65, 0);
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(firstRoad, secondRoad),
                null,
                null,
                null,
                null,
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(firstRoad, Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(secondRoad, Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, firstRoad, Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, secondRoad, Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                List.of(firstRoad, secondRoad, foundation),
                null,
                null,
                null
        );
        List<Object> snapshots = invokeCaptureRoadRollbackSnapshots(
                List.of(firstRoad, secondRoad, foundation, headspace),
                pos -> {
                    if (pos.equals(firstRoad)) {
                        return Blocks.GRASS_BLOCK.defaultBlockState();
                    }
                    if (pos.equals(secondRoad)) {
                        return Blocks.WATER.defaultBlockState();
                    }
                    if (pos.equals(foundation)) {
                        return Blocks.DIRT.defaultBlockState();
                    }
                    if (pos.equals(headspace)) {
                        return Blocks.POPPY.defaultBlockState();
                    }
                    return Blocks.AIR.defaultBlockState();
                }
        );
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();

        int rollbackIndex = invokeApplyRoadRollbackBatch(plan, snapshots, 0, 2, states::put);

        assertEquals(2, rollbackIndex);
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), states.get(firstRoad));
        assertEquals(Blocks.WATER.defaultBlockState(), states.get(secondRoad));
        assertFalse(states.containsKey(foundation));
        assertFalse(states.containsKey(headspace));

        rollbackIndex = invokeApplyRoadRollbackBatch(plan, snapshots, rollbackIndex, 8, states::put);

        assertEquals(4, rollbackIndex);
        assertEquals(Blocks.DIRT.defaultBlockState(), states.get(foundation));
        assertEquals(Blocks.POPPY.defaultBlockState(), states.get(headspace));
    }

    @Test
    void incrementalRollbackRemovesOwnedBlocksThatHaveNoSnapshot() {
        BlockPos road = new BlockPos(0, 64, 0);
        BlockPos support = new BlockPos(0, 63, 0);
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(road, road.east()),
                null,
                null,
                null,
                null,
                List.of(new RoadGeometryPlanner.GhostRoadBlock(road, Blocks.SPRUCE_SLAB.defaultBlockState())),
                List.of(new RoadGeometryPlanner.RoadBuildStep(0, road, Blocks.SPRUCE_SLAB.defaultBlockState())),
                List.of(),
                List.of(road, support),
                null,
                null,
                null
        );
        List<Object> snapshots = invokeCaptureRoadRollbackSnapshots(
                List.of(road),
                pos -> Blocks.GRASS_BLOCK.defaultBlockState()
        );
        Map<BlockPos, net.minecraft.world.level.block.state.BlockState> states = new HashMap<>();

        int rollbackIndex = invokeApplyRoadRollbackBatch(plan, snapshots, 0, 8, states::put);

        assertEquals(2, rollbackIndex);
        assertEquals(Blocks.GRASS_BLOCK.defaultBlockState(), states.get(road));
        assertEquals(Blocks.AIR.defaultBlockState(), states.get(support));
    }

    @Test
    void rollbackProgressRunsFasterThanForwardBuildProgress() {
        double buildProgress = invokeRoadBuildProgressPerTick(120, 1.0D);
        double rollbackProgress = invokeRoadRollbackProgressPerTick(120, 1.0D);

        assertTrue(rollbackProgress > buildProgress);
    }

    private static RoadPlacementPlan widenedPlanWithPartialOwnedBlocks() {
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = List.of(
                new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(-1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(0, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(1, 70, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        return new RoadPlacementPlan(
                List.of(new BlockPos(0, 70, 0), new BlockPos(1, 70, 0)),
                null,
                null,
                null,
                null,
                ghostBlocks,
                buildSteps,
                List.of(),
                List.of(new BlockPos(0, 70, 0)),
                null,
                null,
                null
        );
    }

    private static List<BlockPos> invokeRoadbedTopFromFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("deriveRoadbedTopFromRoadSurfaceFootprint", List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect roadbed-top footprint derivation", ex);
        }
    }

    private static List<BlockPos> invokeOwnedRoadBlocksFromFootprint(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                                      List<BlockPos> terrainEdits) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("collectOwnedRoadBlocks", List.class, List.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks, terrainEdits);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect widened road owned-block collection", ex);
        }
    }

    private static List<BlockPos> invokeResolvedRoadOwnedBlocks(ServerLevel level, RoadPlacementPlan plan) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadOwnedBlocks", ServerLevel.class, RoadPlacementPlan.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, level, plan);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect resolved road ownership fallback", ex);
        }
    }

    private static List<BlockPos> invokeCaptureFoundationFromLookup(List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks,
                                                                    Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blockLookup) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("captureRoadFoundationFromStateLookup", List.class, Function.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, ghostBlocks, blockLookup);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect live support/foundation fallback capture", ex);
        }
    }

    private static List<BlockPos> invokeRoadRollbackTrackedPositions(ServerLevel level, RoadPlacementPlan plan) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadRollbackTrackedPositions", ServerLevel.class, RoadPlacementPlan.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<BlockPos> result = (List<BlockPos>) method.invoke(null, level, plan);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect tracked rollback positions", ex);
        }
    }

    private static List<Object> invokeCaptureRoadRollbackSnapshots(List<BlockPos> positions,
                                                                   Function<BlockPos, net.minecraft.world.level.block.state.BlockState> blockLookup) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("captureRoadRollbackStatesFromStateLookup", List.class, Function.class);
            method.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<Object> result = (List<Object>) method.invoke(null, positions, blockLookup);
            return result;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect rollback snapshot capture", ex);
        }
    }

    private static void invokeRestoreRoadRollbackSnapshots(List<Object> snapshots,
                                                           java.util.function.BiConsumer<BlockPos, net.minecraft.world.level.block.state.BlockState> setter) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("restoreRoadRollbackStatesWithSetter", List.class, java.util.function.BiConsumer.class);
            method.setAccessible(true);
            method.invoke(null, snapshots, setter);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect rollback snapshot restore", ex);
        }
    }

    private static boolean invokeIsRoadPlacementReplaceable(net.minecraft.world.level.block.state.BlockState state) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("isRoadPlacementReplaceableForTest", net.minecraft.world.level.block.state.BlockState.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, state);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road replaceable classification", ex);
        }
    }

    private static boolean invokeIsRoadBuildStepPlaced(net.minecraft.world.level.block.state.BlockState currentState,
                                                       RoadGeometryPlanner.RoadBuildStep step) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("isRoadBuildStepPlaced", net.minecraft.world.level.block.state.BlockState.class, RoadGeometryPlanner.RoadBuildStep.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, currentState, step);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road build step completion matching", ex);
        }
    }

    private static boolean invokeCancelActiveRoadConstruction(ServerLevel level, String roadId) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("cancelActiveRoadConstruction", ServerLevel.class, String.class);
            method.setAccessible(true);
            return (boolean) method.invoke(null, level, roadId);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect cancel road lifecycle", ex);
        }
    }

    private static void invokeClearActiveRoadRuntimeState(String roadId) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("clearActiveRoadRuntimeState", String.class);
            method.setAccessible(true);
            method.invoke(null, roadId);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect active road runtime cleanup", ex);
        }
    }

    private static Object newRoadConstructionJob(ServerLevel level, String roadId) {
        return newRoadConstructionJob(level, roadId, new RoadPlacementPlan(List.of(), null, null, null, null, List.of(), List.of(), List.of(), List.of(), null, null, null));
    }

    private static Object newRoadConstructionJob(ServerLevel level, String roadId, RoadPlacementPlan plan) {
        return newRoadConstructionJob(level, roadId, plan, List.of(), 0, 0.0D, false, 0, false);
    }

    private static Object newRoadConstructionJob(ServerLevel level,
                                                 String roadId,
                                                 RoadPlacementPlan plan,
                                                 List<?> rollbackStates,
                                                 int placedStepCount,
                                                 double progressSteps,
                                                 boolean rollbackActive,
                                                 int rollbackActionIndex,
                                                 boolean removeRoadNetworkOnComplete) {
        try {
            Class<?> jobClass = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager$RoadConstructionJob");
            Constructor<?> constructor = java.util.Arrays.stream(jobClass.getDeclaredConstructors())
                    .filter(candidate -> candidate.getParameterCount() == 14 || candidate.getParameterCount() == 15)
                    .findFirst()
                    .orElseThrow();
            constructor.setAccessible(true);
            Object[] arguments = constructor.getParameterCount() == 15
                    ? new Object[] {
                    level,
                    roadId,
                    UUID.randomUUID(),
                    "town_a",
                    "nation_a",
                    "Town A",
                    "Town B",
                    plan,
                    rollbackStates,
                    placedStepCount,
                    progressSteps,
                    rollbackActive,
                    rollbackActionIndex,
                    removeRoadNetworkOnComplete,
                    java.util.Set.of()
            }
                    : new Object[] {
                    level,
                    roadId,
                    UUID.randomUUID(),
                    "town_a",
                    "nation_a",
                    "Town A",
                    "Town B",
                    plan,
                    rollbackStates,
                    placedStepCount,
                    progressSteps,
                    rollbackActive,
                    rollbackActionIndex,
                    removeRoadNetworkOnComplete
            };
            return constructor.newInstance(arguments);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to construct test road job", ex);
        }
    }

    private static int invokeApplyRoadRollbackBatch(RoadPlacementPlan plan,
                                                    List<Object> rollbackStates,
                                                    int rollbackIndex,
                                                    int actionCount,
                                                    java.util.function.BiConsumer<BlockPos, net.minecraft.world.level.block.state.BlockState> setter) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("applyRoadRollbackBatchWithSetter", ServerLevel.class, RoadPlacementPlan.class, List.class, int.class, int.class, java.util.function.BiConsumer.class);
            method.setAccessible(true);
            return (int) method.invoke(null, null, plan, rollbackStates, rollbackIndex, actionCount, setter);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect incremental road rollback batching", ex);
        }
    }

    private static double invokeRoadBuildProgressPerTick(int totalSteps, double speedMultiplier) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadBuildProgressPerTick", int.class, double.class);
            method.setAccessible(true);
            return (double) method.invoke(null, totalSteps, speedMultiplier);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect forward road build speed", ex);
        }
    }

    private static double invokeRoadRollbackProgressPerTick(int totalActions, double speedMultiplier) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadRollbackProgressPerTick", int.class, double.class);
            method.setAccessible(true);
            return (double) method.invoke(null, totalActions, speedMultiplier);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect rollback road speed", ex);
        }
    }

    private static Object readRecordComponent(Object instance, String accessor) {
        try {
            return instance.getClass().getDeclaredMethod(accessor).invoke(instance);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to read record component " + accessor, ex);
        }
    }

    private static Field staticField(String name) {
        try {
            Field field = Class.forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager").getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to access manager field " + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readStaticMap(String name) {
        try {
            return (Map<String, Object>) staticField(name).get(null);
        } catch (IllegalAccessException ex) {
            throw new AssertionError("Unable to read manager map " + name, ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Unsafe unsafe = (Unsafe) field.get(null);
            return (T) unsafe.allocateInstance(type);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to allocate test instance for " + type.getName(), ex);
        }
    }

    private static void restoreMapEntry(Map<String, Object> map, String key, Object previous) {
        if (previous == null) {
            map.remove(key);
            return;
        }
        map.put(key, previous);
    }
}
