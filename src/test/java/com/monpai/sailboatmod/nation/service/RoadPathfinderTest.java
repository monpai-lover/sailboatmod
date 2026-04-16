package com.monpai.sailboatmod.nation.service;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPathfinderTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void routeColumnTreatsWaterAsBlockedDuringLandOnlyPass() {
        assertTrue(RoadPathfinder.isBridgeBlockedForModeForTest(true, false));
    }

    @Test
    void routeColumnAllowsWaterDuringFallbackPass() {
        assertFalse(RoadPathfinder.isBridgeBlockedForModeForTest(true, true));
    }

    @Test
    void emptyGroundRouteExposesStructuredFailureReason() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        RoadPathfinder.PlannedPathResult result = RoadPathfinder.findGroundPathForPlan(
                level,
                new BlockPos(-847, 62, 215),
                new BlockPos(-623, 66, 219),
                java.util.Set.of(),
                java.util.Set.of()
        );

        assertEquals(RoadPlanningFailureReason.NO_CONTINUOUS_GROUND_ROUTE, result.failureReason());
    }

    @Test
    void emptyGroundRouteExposesSearchExhaustedFailureReasonWhenPlannerReportsIt() {
        RoadPathfinder.PlannedPathResult result = new RoadPathfinder.PlannedPathResult(
                List.of(),
                RoadPlanningFailureReason.SEARCH_EXHAUSTED
        );

        assertEquals(RoadPlanningFailureReason.SEARCH_EXHAUSTED, result.failureReason());
    }

    @Test
    void snapshotBackedGroundPathAvoidsNewHeightmapQueriesForSeededColumns() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        TestServerLevel baselineLevel = allocate(TestServerLevel.class);
        baselineLevel.blockStates = new HashMap<>();
        baselineLevel.surfaceHeights = new HashMap<>();
        baselineLevel.biome = level.biome;

        Map<Long, RoadPlanningSnapshot.ColumnSample> columns = new HashMap<>();
        for (int x = 0; x <= 4; x++) {
            for (int z = -1; z <= 1; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.DIRT.defaultBlockState());
                setSurfaceColumn(baselineLevel, x, z, 64, Blocks.DIRT.defaultBlockState());
                columns.put(
                        columnKey(x, z),
                        new RoadPlanningSnapshot.ColumnSample(
                                new BlockPos(x, 64, z),
                                Blocks.DIRT.defaultBlockState(),
                                0,
                                false,
                                64
                        )
                );
            }
        }

        RoadPlanningSnapshot snapshot = new RoadPlanningSnapshot(
                columns,
                false,
                false,
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0)
        );

        RoadPathfinder.PlannedPathResult result = RoadPathfinder.findGroundPathForPlan(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                java.util.Set.of(),
                java.util.Set.of(),
                snapshot
        );
        RoadPathfinder.PlannedPathResult baseline = RoadPathfinder.findGroundPathForPlan(
                baselineLevel,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                java.util.Set.of(),
                java.util.Set.of()
        );

        assertFalse(result.path().isEmpty(), "snapshot-backed path should not be empty");
        assertFalse(baseline.path().isEmpty(), "baseline path should not be empty");
        assertTrue(level.surfaceQueries() < baselineLevel.surfaceQueries(),
                () -> "snapshotQueries=" + level.surfaceQueries() + " baselineQueries=" + baselineLevel.surfaceQueries());
    }

    @Test
    void findPathAllowsRequestedEndpointsEvenWhenTheirColumnsAreBlocked() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 4; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.DIRT.defaultBlockState());
        }

        List<BlockPos> path = RoadPathfinder.findPath(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                java.util.Set.of(columnKey(0, 0), columnKey(4, 0)),
                java.util.Set.of(),
                false
        );

        assertFalse(path.isEmpty());
        assertEquals(new BlockPos(0, 64, 0), path.get(0));
        assertEquals(new BlockPos(4, 64, 0), path.get(path.size() - 1));
    }

    @Test
    void findPathAllowsRequestedEndpointsEvenWhenTheirColumnsAreExcluded() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 4; x++) {
            level.surfaceHeights.put(columnKey(x, 1), 64);
            level.blockStates.put(new BlockPos(x, 64, 1).asLong(), Blocks.DIRT.defaultBlockState());
        }

        List<BlockPos> path = RoadPathfinder.findPath(
                level,
                new BlockPos(0, 64, 1),
                new BlockPos(4, 64, 1),
                java.util.Set.of(),
                java.util.Set.of(columnKey(0, 1), columnKey(4, 1)),
                false
        );

        assertFalse(path.isEmpty());
        assertEquals(new BlockPos(0, 64, 1), path.get(0));
        assertEquals(new BlockPos(4, 64, 1), path.get(path.size() - 1));
    }

    @Test
    void findSurfaceCanReachRiverbedBelowFiveBlocksOfWater() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(0, 0), 64);
        for (int y = 64; y >= 58; y--) {
            level.blockStates.put(new BlockPos(0, y, 0).asLong(), Blocks.WATER.defaultBlockState());
        }
        level.blockStates.put(new BlockPos(0, 57, 0).asLong(), Blocks.DIRT.defaultBlockState());

        BlockPos surface = RoadPathfinder.findSurfaceForTest(level, 0, 0);

        assertNotNull(surface);
        assertTrue(surface.getY() <= 57);
    }

    @Test
    void findSurfaceSkipsTreeTrunkAndFindsActualGround() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(0, 0), 70);
        level.blockStates.put(new BlockPos(0, 70, 0).asLong(), Blocks.OAK_LOG.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 69, 0).asLong(), Blocks.OAK_LEAVES.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 68, 0).asLong(), Blocks.AIR.defaultBlockState());
        level.blockStates.put(new BlockPos(0, 64, 0).asLong(), Blocks.DIRT.defaultBlockState());

        BlockPos surface = RoadPathfinder.findSurfaceForTest(level, 0, 0);

        assertNotNull(surface);
        assertEquals(new BlockPos(0, 64, 0), surface);
    }

    @Test
    void columnDiagnosticsExposeBlockingOccupantAboveSurface() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(4, 5), 64);
        level.blockStates.put(new BlockPos(4, 64, 5).asLong(), Blocks.DIRT.defaultBlockState());
        level.blockStates.put(new BlockPos(4, 65, 5).asLong(), Blocks.COBBLESTONE.defaultBlockState());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(4, 70, 5), false);

        assertNotNull(diagnostics.surface());
        assertEquals(new BlockPos(4, 64, 5), diagnostics.surface());
        assertTrue(diagnostics.blockedByOccupant());
        assertFalse(diagnostics.blockedByPlanner());
        assertFalse(diagnostics.bridgeRequired());
    }

    @Test
    void bridgeFallbackPreservesRaisedDeckAnchorHeight() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(8, 9), 64);
        for (int y = 64; y >= 61; y--) {
            level.blockStates.put(new BlockPos(8, y, 9).asLong(), Blocks.WATER.defaultBlockState());
        }
        level.blockStates.put(new BlockPos(8, 60, 9).asLong(), Blocks.STONE.defaultBlockState());
        assertTrue(level.getBlockState(new BlockPos(8, 61, 9)).liquid());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(8, 67, 9), true);

        assertNotNull(diagnostics.surface());
        assertEquals(new BlockPos(8, 67, 9), diagnostics.surface());
        assertTrue(diagnostics.bridgeRequired());
        assertFalse(diagnostics.bridgeBlockedByMode());
    }

    @Test
    void bridgeFallbackPreservesRaisedDeckAnchorOverClearLandColumn() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(9, 9), 60);
        level.blockStates.put(new BlockPos(9, 60, 9).asLong(), Blocks.STONE.defaultBlockState());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(9, 67, 9), true);

        assertNotNull(diagnostics.surface());
        assertEquals(new BlockPos(9, 67, 9), diagnostics.surface());
        assertFalse(diagnostics.blocked(), diagnostics.reason());
    }

    @Test
    void raisedBridgeDeckAnchorIgnoresLowSupportBlocksBelowDeck() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(12, 4), 63);
        level.blockStates.put(new BlockPos(12, 63, 4).asLong(), Blocks.AIR.defaultBlockState());
        level.blockStates.put(new BlockPos(12, 62, 4).asLong(), Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(12, 61, 4).asLong(), Blocks.OAK_FENCE.defaultBlockState());
        level.blockStates.put(new BlockPos(12, 60, 4).asLong(), Blocks.OAK_FENCE.defaultBlockState());
        level.blockStates.put(new BlockPos(12, 59, 4).asLong(), Blocks.STONE.defaultBlockState());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(12, 63, 4), true);

        assertNotNull(diagnostics.surface());
        assertEquals(new BlockPos(12, 63, 4), diagnostics.surface());
        assertFalse(diagnostics.blocked(), diagnostics.reason());
        assertFalse(diagnostics.blockedByOccupant());
        assertFalse(diagnostics.blockedByHeadroom());
    }

    @Test
    void existingBridgeStairAboveSurfaceDoesNotBlockColumnDiagnostics() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(14, 4), 64);
        level.blockStates.put(new BlockPos(14, 64, 4).asLong(), Blocks.DIRT.defaultBlockState());
        level.blockStates.put(new BlockPos(14, 65, 4).asLong(), Blocks.STONE_BRICK_STAIRS.defaultBlockState());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(14, 64, 4), true);

        assertNotNull(diagnostics.surface());
        assertFalse(diagnostics.blocked(), diagnostics.reason());
        assertFalse(diagnostics.blockedByOccupant());
    }

    @Test
    void elevatedBridgeDeckAnchorOverSolidApproachSupportRemainsTraversable() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        level.surfaceHeights.put(columnKey(16, 4), 59);
        level.blockStates.put(new BlockPos(16, 58, 4).asLong(), Blocks.STONE.defaultBlockState());
        level.blockStates.put(new BlockPos(16, 59, 4).asLong(), Blocks.COBBLESTONE.defaultBlockState());
        level.blockStates.put(new BlockPos(16, 60, 4).asLong(), Blocks.COBBLESTONE.defaultBlockState());
        level.blockStates.put(new BlockPos(16, 61, 4).asLong(), Blocks.COBBLESTONE.defaultBlockState());
        level.blockStates.put(new BlockPos(16, 63, 4).asLong(), Blocks.STONE_BRICKS.defaultBlockState());

        RoadPathfinder.ColumnDiagnostics diagnostics = RoadPathfinder.describeColumnForTest(level, new BlockPos(16, 62, 4), true);

        assertNotNull(diagnostics.surface());
        assertEquals(new BlockPos(16, 62, 4), diagnostics.surface());
        assertFalse(diagnostics.blocked(), diagnostics.reason());
        assertFalse(diagnostics.blockedByOccupant());
        assertFalse(diagnostics.blockedByHeadroom());
    }

    @Test
    void bridgeFallbackProducesElevatedBridgeDeckAcrossWaterSpan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 7, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 8, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 2; x <= 6; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 61, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        List<BlockPos> path = RoadPathfinder.findPath(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                java.util.Set.of(),
                java.util.Set.of(),
                true
        );

        assertFalse(path.isEmpty(), "path should not be empty");
        assertTrue(path.stream().anyMatch(pos -> pos.getX() >= 2 && pos.getX() <= 6 && pos.getY() >= 68), path.toString());
    }

    @Test
    void snapshotBackedBridgeDeckAnchorsStillGenerateAcrossWaterSpan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        Map<Long, RoadPlanningSnapshot.ColumnSample> columns = new HashMap<>();
        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 7, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 8, 0, 64, Blocks.DIRT.defaultBlockState());
        columns.put(columnKey(0, 0), new RoadPlanningSnapshot.ColumnSample(new BlockPos(0, 64, 0), Blocks.DIRT.defaultBlockState(), 0, false, 64));
        columns.put(columnKey(1, 0), new RoadPlanningSnapshot.ColumnSample(new BlockPos(1, 64, 0), Blocks.DIRT.defaultBlockState(), 0, false, 64));
        columns.put(columnKey(7, 0), new RoadPlanningSnapshot.ColumnSample(new BlockPos(7, 64, 0), Blocks.DIRT.defaultBlockState(), 0, false, 64));
        columns.put(columnKey(8, 0), new RoadPlanningSnapshot.ColumnSample(new BlockPos(8, 64, 0), Blocks.DIRT.defaultBlockState(), 0, false, 64));
        for (int x = 2; x <= 6; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 64);
            level.blockStates.put(new BlockPos(x, 64, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 61, 0).asLong(), Blocks.STONE.defaultBlockState());
            columns.put(columnKey(x, 0), new RoadPlanningSnapshot.ColumnSample(
                    new BlockPos(x, 61, 0),
                    Blocks.STONE.defaultBlockState(),
                    2,
                    true,
                    61
            ));
        }

        RoadPlanningSnapshot snapshot = new RoadPlanningSnapshot(
                columns,
                false,
                false,
                List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0)
        );

        List<BlockPos> anchors = RoadPathfinder.collectBridgeDeckAnchors(
                level,
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                java.util.Set.of(),
                snapshot
        );

        assertFalse(anchors.isEmpty(), "snapshot-backed bridge deck anchors should not be empty");
        assertTrue(anchors.stream().anyMatch(pos -> pos.getX() >= 2 && pos.getX() <= 6 && pos.getY() >= 69), anchors.toString());
    }

    @Test
    void islandToMainlandFortyFiveBlockGapBuildsBridgePathWhenWaterFallbackIsAllowed() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = -8; x <= 56; x++) {
            for (int z = -8; z <= 8; z++) {
                level.surfaceHeights.put(columnKey(x, z), 64);
                level.blockStates.put(new BlockPos(x, 64, z).asLong(), Blocks.WATER.defaultBlockState());
                level.blockStates.put(new BlockPos(x, 63, z).asLong(), Blocks.WATER.defaultBlockState());
                level.blockStates.put(new BlockPos(x, 62, z).asLong(), Blocks.WATER.defaultBlockState());
                level.blockStates.put(new BlockPos(x, 61, z).asLong(), Blocks.STONE.defaultBlockState());
            }
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }
        for (int x = 47; x <= 64; x++) {
            for (int z = -8; z <= 8; z++) {
                setSurfaceColumn(level, x, z, 64, Blocks.GRASS_BLOCK.defaultBlockState());
            }
        }

        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(48, 64, 0);
        RoadPlanningSnapshot snapshot = RoadPlanningSnapshotBuilder.buildForTest(
                level,
                start,
                end,
                java.util.Set.of(),
                java.util.Set.of()
        );

        List<BlockPos> path = RoadPathfinder.findPath(
                level,
                start,
                end,
                java.util.Set.of(),
                java.util.Set.of(),
                true,
                snapshot
        );

        assertTrue(snapshot.sourceIslandLike(), "source should be classified as island-like");
        assertFalse(snapshot.targetIslandLike(), "mainland target should not be island-like");
        assertFalse(path.isEmpty(), "45-block island-to-mainland path should not be empty");
        assertEquals(start, path.get(0));
        assertEquals(end, path.get(path.size() - 1));
        assertTrue(
                path.stream().anyMatch(pos -> pos.getX() >= 3 && pos.getX() <= 46),
                path.toString()
        );
    }

    @Test
    void findPathKeepsRaisedExistingDeckInsteadOfDroppingToBlockedSupportSurface() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 3; x++) {
            level.surfaceHeights.put(columnKey(x, 0), 59);
            level.blockStates.put(new BlockPos(x, 58, 0).asLong(), Blocks.STONE.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 59, 0).asLong(), Blocks.OAK_FENCE.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 63, 0).asLong(), Blocks.STONE_BRICK_SLAB.defaultBlockState());
        }

        List<BlockPos> path = RoadPathfinder.findPath(
                level,
                new BlockPos(0, 62, 0),
                new BlockPos(3, 62, 0),
                java.util.Set.of(),
                java.util.Set.of(),
                true
        );

        assertFalse(path.isEmpty(), "raised deck path should not be empty");
        assertEquals(new BlockPos(0, 62, 0), path.get(0));
        assertEquals(new BlockPos(3, 62, 0), path.get(path.size() - 1));
        assertTrue(path.stream().allMatch(pos -> pos.getY() >= 62), path.toString());
    }

    @Test
    void returnedPathNormalizationRepairsMissingRequestedEndpoints() {
        List<BlockPos> normalized = RoadPathfinder.normalizeReturnedPathForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                List.of(
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0)
                ),
                normalized
        );
    }

    @Test
    void returnedPathNormalizationRejectsDisconnectedCandidate() {
        List<BlockPos> normalized = RoadPathfinder.normalizeReturnedPathForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                List.of(
                        new BlockPos(1, 64, 0),
                        new BlockPos(3, 64, 0)
                )
        );

        assertTrue(normalized.isEmpty());
    }

    @Test
    void returnedPathNormalizationAllowsElevatedBridgeDeckWhenColumnsStayAdjacent() {
        List<BlockPos> normalized = RoadPathfinder.normalizeReturnedPathForTest(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                List.of(
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0)
                )
        );

        assertFalse(normalized.isEmpty());
        assertEquals(new BlockPos(2, 68, 0), normalized.get(2));
    }

    @Test
    void returnedPathNormalizationTreatsSamePointRequestAsSatisfiedNoOp() {
        BlockPos anchor = new BlockPos(8, 64, 8);

        List<BlockPos> normalized = RoadPathfinder.normalizeReturnedPathForTest(
                anchor,
                anchor,
                List.of(anchor)
        );

        assertEquals(List.of(anchor), normalized);
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
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
        private int surfaceQueries;

        private TestServerLevel() {
            super(null, command -> { }, null, null, null, null, null, false, 0L, List.of(), false, null);
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            return blockStates.getOrDefault(pos.asLong(), Blocks.AIR.defaultBlockState());
        }

        @Override
        public BlockPos getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types heightmapType, BlockPos pos) {
            surfaceQueries++;
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

        int surfaceQueries() {
            return surfaceQueries;
        }
    }
}
