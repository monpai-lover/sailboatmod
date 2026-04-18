package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadGeometryPlanner;
import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.core.Holder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
    void rollbackTrackedPositionsIncludeBridgePierLightsAndRailingLights() {
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
        List<BlockPos> lightPositions = plan.corridorPlan().slices().stream()
                .flatMap(slice -> java.util.stream.Stream.concat(
                        slice.railingLightPositions().stream(),
                        slice.pierLightPositions().stream()
                ))
                .toList();

        assertFalse(lightPositions.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(tracked.containsAll(lightPositions), () -> tracked.toString());
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
        assertTrue(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.OAK_LOG.defaultBlockState()));
        assertTrue(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.GRASS.defaultBlockState()));
        assertFalse(StructureConstructionManager.clearanceStateIsSafeToRemoveForTest(Blocks.CHEST.defaultBlockState()));
    }

    @Test
    void landRoadPlacementAllowsNaturalWoodInHeadspaceButStillRejectsPlacedStorage() {
        StructureConstructionManager.TestRoadPlacementResult naturalWoodResult =
                StructureConstructionManager.placeRoadColumnForTest(
                        Blocks.GRASS_BLOCK.defaultBlockState(),
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.OAK_LOG.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                );
        StructureConstructionManager.TestRoadPlacementResult storageResult =
                StructureConstructionManager.placeRoadColumnForTest(
                        Blocks.GRASS_BLOCK.defaultBlockState(),
                        Blocks.DIRT.defaultBlockState(),
                        Blocks.CHEST.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState()
                );

        assertEquals(Blocks.STONE_BRICK_SLAB, naturalWoodResult.surfaceState().getBlock());
        assertEquals(63, naturalWoodResult.foundationTopY());
        assertEquals(Blocks.GRASS_BLOCK, storageResult.surfaceState().getBlock());
        assertEquals(Integer.MIN_VALUE, storageResult.foundationTopY());
    }

    @Test
    void roadProgressPercentShowsMinimumOnePercentAfterFirstCompletedStep() {
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0)),
                null,
                null,
                null,
                null,
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(2, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(1, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(2, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(3, new BlockPos(3, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(4, new BlockPos(4, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(5, new BlockPos(5, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(6, new BlockPos(6, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(7, new BlockPos(7, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(8, new BlockPos(8, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(9, new BlockPos(9, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(10, new BlockPos(10, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(11, new BlockPos(11, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(12, new BlockPos(12, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(13, new BlockPos(13, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(14, new BlockPos(14, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(15, new BlockPos(15, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(16, new BlockPos(16, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(17, new BlockPos(17, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(18, new BlockPos(18, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(19, new BlockPos(19, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(20, new BlockPos(20, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(21, new BlockPos(21, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(22, new BlockPos(22, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(23, new BlockPos(23, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(24, new BlockPos(24, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(25, new BlockPos(25, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(26, new BlockPos(26, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(27, new BlockPos(27, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(28, new BlockPos(28, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(29, new BlockPos(29, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(30, new BlockPos(30, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(31, new BlockPos(31, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(32, new BlockPos(32, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(33, new BlockPos(33, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(34, new BlockPos(34, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(35, new BlockPos(35, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(36, new BlockPos(36, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(37, new BlockPos(37, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(38, new BlockPos(38, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(39, new BlockPos(39, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(40, new BlockPos(40, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(41, new BlockPos(41, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(42, new BlockPos(42, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(43, new BlockPos(43, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(44, new BlockPos(44, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(45, new BlockPos(45, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(46, new BlockPos(46, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(47, new BlockPos(47, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(48, new BlockPos(48, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(49, new BlockPos(49, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(50, new BlockPos(50, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(51, new BlockPos(51, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(52, new BlockPos(52, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(53, new BlockPos(53, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(54, new BlockPos(54, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(55, new BlockPos(55, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(56, new BlockPos(56, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(57, new BlockPos(57, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(58, new BlockPos(58, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(59, new BlockPos(59, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(60, new BlockPos(60, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(61, new BlockPos(61, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(62, new BlockPos(62, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(63, new BlockPos(63, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(64, new BlockPos(64, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(65, new BlockPos(65, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(66, new BlockPos(66, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(67, new BlockPos(67, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(68, new BlockPos(68, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(69, new BlockPos(69, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(70, new BlockPos(70, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(71, new BlockPos(71, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(72, new BlockPos(72, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(73, new BlockPos(73, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(74, new BlockPos(74, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(75, new BlockPos(75, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(76, new BlockPos(76, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(77, new BlockPos(77, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(78, new BlockPos(78, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(79, new BlockPos(79, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(80, new BlockPos(80, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(81, new BlockPos(81, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(82, new BlockPos(82, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(83, new BlockPos(83, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(84, new BlockPos(84, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(85, new BlockPos(85, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(86, new BlockPos(86, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(87, new BlockPos(87, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(88, new BlockPos(88, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(89, new BlockPos(89, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(90, new BlockPos(90, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(91, new BlockPos(91, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(92, new BlockPos(92, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(93, new BlockPos(93, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(94, new BlockPos(94, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(95, new BlockPos(95, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(96, new BlockPos(96, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(97, new BlockPos(97, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(98, new BlockPos(98, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(99, new BlockPos(99, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(100, new BlockPos(100, 64, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                List.of(),
                null,
                null,
                null
        );

        assertEquals(1, invokeRoadProgressPercent(plan, 1));
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
    void placedRoadStepRejectsEquivalentRoadFamilyVariantUntilExactShapeIsBuilt() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        assertFalse(invokeIsRoadBuildStepPlaced(
                Blocks.STONE_BRICK_STAIRS.defaultBlockState(),
                step
        ));
    }

    @Test
    void placedRoadStepRejectsEquivalentStairWithDifferentFacingUntilExactFacingIsBuilt() {
        RoadGeometryPlanner.RoadBuildStep step = new RoadGeometryPlanner.RoadBuildStep(
                0,
                new BlockPos(0, 64, 0),
                Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING, net.minecraft.core.Direction.WEST)
        );

        assertFalse(invokeIsRoadBuildStepPlaced(
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
    void scheduledRoadJobWithRemainingBuildStepsAdvancesDuringTick() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                List.of(),
                List.of()
        );
        seedSupportedRoadbed(level, plan);
        String roadId = "manual|tick|town_a|town_b";
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan, List.of(), 0, plan.buildSteps().size(), false, 0, false));

        try {
            StructureConstructionManager.tickRoadConstructions(level);

            Object updated = activeRoads.get(roadId);
            assertNotNull(updated);
            assertTrue((int) readRecordComponent(updated, "placedStepCount") > 0, "tick should consume at least one road build step");
        } finally {
            restoreMapEntry(activeRoads, roadId, previous);
        }
    }

    @Test
    void roadConstructionRuntimeDoesNotDiscardValidJobJustBecauseProgressStartsAtZero() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0)
                ),
                List.of(),
                List.of()
        );
        seedSupportedRoadbed(level, plan);
        String roadId = "manual|runtime|town_a|town_b";
        @SuppressWarnings("unchecked")
        Map<String, Object> activeRoads = readStaticMap("ACTIVE_ROAD_CONSTRUCTIONS");
        Object previous = activeRoads.put(roadId, newRoadConstructionJob(level, roadId, plan, List.of(), 0, 0.0D, false, 0, false));

        try {
            StructureConstructionManager.tickRoadConstructions(level);
            assertNotNull(activeRoads.get(roadId), "valid road runtime should remain active after first tick");
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

    private static int invokeRoadProgressPercent(RoadPlacementPlan plan, int placedStepCount) {
        try {
            var method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadProgressPercent", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, placedStepCount);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road progress percent", ex);
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
        public boolean setBlock(BlockPos pos, BlockState newState, int flags) {
            blockStates.put(pos.asLong(), newState);
            return true;
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
        public <T extends net.minecraft.core.particles.ParticleOptions> int sendParticles(T particle,
                                                                                           double x,
                                                                                           double y,
                                                                                           double z,
                                                                                           int count,
                                                                                           double xDist,
                                                                                           double yDist,
                                                                                           double zDist,
                                                                                           double speed) {
            return 0;
        }

        @Override
        public void playSound(net.minecraft.world.entity.player.Player player,
                              BlockPos pos,
                              net.minecraft.sounds.SoundEvent sound,
                              net.minecraft.sounds.SoundSource source,
                              float volume,
                              float pitch) {
        }
    }

    private static long columnKey(int x, int z) {
        return (((long) x) << 32) ^ (z & 0xffffffffL);
    }

    private static void seedSupportedRoadbed(TestServerLevel level, RoadPlacementPlan plan) {
        for (BlockPos pos : plan.centerPath()) {
            level.blockStates.put(pos.below().asLong(), Blocks.DIRT.defaultBlockState());
            level.blockStates.put(pos.asLong(), Blocks.GRASS_BLOCK.defaultBlockState());
            level.surfaceHeights.put(columnKey(pos.getX(), pos.getZ()), pos.getY());
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
