package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.construction.RoadCorridorPlan;
import com.monpai.sailboatmod.construction.RoadPlacementPlan;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructureConstructionManagerRoadLinkTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void previewRoadConnectionPrefersRoadTargetBonus() {
        StructureConstructionManager.PreviewRoadConnection road = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(4, 64, 0)),
                StructureConstructionManager.PreviewRoadTargetKind.ROAD,
                new BlockPos(4, 64, 0)
        );
        StructureConstructionManager.PreviewRoadConnection structure = new StructureConstructionManager.PreviewRoadConnection(
                List.of(new BlockPos(0, 64, 0), new BlockPos(3, 64, 1)),
                StructureConstructionManager.PreviewRoadTargetKind.STRUCTURE,
                new BlockPos(3, 64, 1)
        );

        StructureConstructionManager.PreviewRoadConnection chosen =
                StructureConstructionManager.choosePreviewConnectionForTest(List.of(structure, road), 0);

        assertEquals(StructureConstructionManager.PreviewRoadTargetKind.ROAD, chosen.targetKind());
    }

    @Test
    void manualBridgeLinkProducesOwnedSupportAndLightingArtifacts() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.supportPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.pierLightPositions().isEmpty()));
        assertTrue(plan.corridorPlan().slices().stream().anyMatch(slice -> !slice.railingLightPositions().isEmpty()));
    }

    @Test
    void shortWaterBridgeDoesNotCreatePierSupportColumns() {
        RoadPlacementPlan plan = shortBridgePlanFixture();

        List<RoadCorridorPlan.CorridorSlice> supportSlices = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .toList();

        assertTrue(supportSlices.isEmpty(), () -> supportSlices.toString());
    }

    @Test
    void longWaterBridgeUsesStoneDeckSlabsAndStonePierMaterials() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICKS)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.COBBLESTONE_WALL)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_SLAB)));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.SPRUCE_FENCE)));
    }

    @Test
    void elevatedBridgePlanBuildsPierGhostsAllTheWayToFoundation() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
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
                )
        );

        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.pos().getY() == 41 && block.state().is(Blocks.STONE_BRICKS)),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void bridgeRailingAndLightsStayWithinBridgeSurfaceFootprint() {
        RoadPlacementPlan plan = StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 5)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 3))
        );

        java.util.Set<Long> bridgeSurfaceColumns = plan.corridorPlan().slices().stream()
                .filter(slice -> slice.segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH)
                .flatMap(slice -> slice.surfacePositions().stream())
                .map(pos -> BlockPos.asLong(pos.getX(), 0, pos.getZ()))
                .collect(java.util.stream.Collectors.toSet());

        assertTrue(plan.ghostBlocks().stream()
                .filter(block -> block.state().is(Blocks.COBBLESTONE_WALL) || block.state().is(Blocks.LANTERN))
                .allMatch(block -> bridgeSurfaceColumns.contains(BlockPos.asLong(block.pos().getX(), 0, block.pos().getZ()))),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void shorelineLandRouteDoesNotCreateBridgeRangesJustBecauseWaterIsNearby() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 3; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
            setSurfaceColumn(level, x, 1, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 1).asLong(), Blocks.STONE.defaultBlockState());
        }

        List<RoadPlacementPlan.BridgeRange> ranges = invokeDetectBridgeRanges(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0)
                )
        );

        assertTrue(ranges.isEmpty(), String.valueOf(ranges));
    }

    @Test
    void elevatedWaterCrossingProducesBridgeSpanAndRampSlabsWithoutStairs() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 3; x++) {
            setSurfaceColumn(level, x, 0, 63, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 67, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 67, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertFalse(plan.bridgeRanges().isEmpty(), String.valueOf(plan.bridgeRanges()));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)),
                () -> plan.ghostBlocks().toString());
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void detectBridgeRangesMergesShortLandGapBetweenWaterCrossings() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 3, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(3, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());

        List<RoadPlacementPlan.BridgeRange> ranges = invokeDetectBridgeRanges(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 64, 0),
                        new BlockPos(3, 64, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertEquals(List.of(new RoadPlacementPlan.BridgeRange(1, 3)), ranges);
    }

    @Test
    void mergedBridgeRangeKeepsShortLandGapAsSupportedBridgeSpan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 1, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(1, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 2, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 3, 0, 63, Blocks.WATER.defaultBlockState());
        level.blockStates.put(new BlockPos(3, 62, 0).asLong(), Blocks.STONE.defaultBlockState());
        setSurfaceColumn(level, 4, 0, 64, Blocks.DIRT.defaultBlockState());

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 67, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 67, 0),
                        new BlockPos(4, 64, 0)
                )
        );

        assertTrue(plan.bridgeRanges().contains(new RoadPlacementPlan.BridgeRange(1, 3)), String.valueOf(plan.bridgeRanges()));
        assertEquals(RoadCorridorPlan.SegmentKind.NON_NAVIGABLE_BRIDGE_SUPPORT_SPAN, plan.corridorPlan().slices().get(2).segmentKind());
    }

    @Test
    void bridgeLightsBuildAsWallPillarsWithLanternsOnTop() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        assertTrue(plan.ghostBlocks().stream().anyMatch(block ->
                        block.state().is(Blocks.LANTERN)
                                && hasGhost(plan, block.pos().below(), Blocks.COBBLESTONE_WALL)
                                && hasGhost(plan, block.pos().below(2), Blocks.COBBLESTONE_WALL)),
                () -> plan.ghostBlocks().toString());
    }

    @Test
    void longWaterBridgeUsesDiscretePierAnchorsInsteadOfContinuousSupport() {
        RoadPlacementPlan plan = longBridgePlanFixture();

        List<Integer> supportIndexes = plan.corridorPlan().slices().stream()
                .filter(slice -> !slice.supportPositions().isEmpty())
                .map(RoadCorridorPlan.CorridorSlice::index)
                .toList();

        assertFalse(supportIndexes.isEmpty(), () -> plan.corridorPlan().slices().toString());
        assertTrue(supportIndexes.size() < 6, supportIndexes.toString());
        assertTrue(supportIndexes.stream().allMatch(index -> index > 1 && index < 8), supportIndexes.toString());
    }

    @Test
    void longBridgePlanDoesNotFillEveryDeckColumnDownward() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        setSurfaceColumn(level, 0, 0, 64, Blocks.DIRT.defaultBlockState());
        setSurfaceColumn(level, 9, 0, 64, Blocks.DIRT.defaultBlockState());
        for (int x = 1; x <= 8; x++) {
            setSurfaceColumn(level, x, 0, 40, Blocks.WATER.defaultBlockState());
            level.blockStates.put(new BlockPos(x, 39, 0).asLong(), Blocks.STONE.defaultBlockState());
        }

        RoadPlacementPlan plan = invokeCreateRoadPlacementPlan(
                level,
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
                )
        );

        assertFalse(hasGhost(plan, new BlockPos(4, 66, 0), Blocks.STONE_BRICKS), () -> plan.ghostBlocks().toString());
        assertFalse(hasGhost(plan, new BlockPos(5, 66, 0), Blocks.STONE_BRICKS), () -> plan.ghostBlocks().toString());
    }

    @Test
    void alreadyBuiltRoadSegmentsAreExcludedFromNewConstructionPlan() {
        TestServerLevel level = allocate(TestServerLevel.class);
        level.blockStates = new HashMap<>();
        level.surfaceHeights = new HashMap<>();
        level.biome = Holder.direct(allocate(Biome.class));

        for (int x = 0; x <= 2; x++) {
            setSurfaceColumn(level, x, 0, 64, Blocks.DIRT.defaultBlockState());
        }

        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0)
        );

        RoadPlacementPlan initialPlan = invokeCreateRoadPlacementPlan(level, centerPath);
        assertFalse(initialPlan.buildSteps().isEmpty());

        initialPlan.ghostBlocks().forEach(block -> level.blockStates.put(block.pos().asLong(), block.state()));

        RoadPlacementPlan repeatedPlan = invokeCreateRoadPlacementPlan(level, centerPath);

        assertTrue(repeatedPlan.ghostBlocks().isEmpty(), () -> repeatedPlan.ghostBlocks().toString());
        assertTrue(repeatedPlan.buildSteps().isEmpty(), () -> repeatedPlan.buildSteps().toString());
    }

    @Test
    void coreExclusionFilterRemovesConstructionFootprintInsideCoreRadius() {
        List<BlockPos> filtered = invokeFilterCoreExcludedPositions(
                List.of(
                        new BlockPos(100, 64, 100),
                        new BlockPos(105, 64, 105),
                        new BlockPos(106, 64, 100)
                ),
                List.of(new BlockPos(100, 64, 100)),
                List.of()
        );

        assertFalse(filtered.contains(new BlockPos(100, 64, 100)));
        assertFalse(filtered.contains(new BlockPos(105, 64, 105)));
        assertTrue(filtered.contains(new BlockPos(106, 64, 100)));
    }

    @Test
    void trimmingExcludedPathEndpointsPreservesOuterBridgeApproach() {
        List<BlockPos> trimmed = invokeTrimExcludedPathEndpoints(
                List.of(
                        new BlockPos(100, 64, 100),
                        new BlockPos(104, 64, 100),
                        new BlockPos(106, 64, 100),
                        new BlockPos(112, 64, 100)
                ),
                java.util.Set.of(
                        BlockPos.asLong(100, 0, 100),
                        BlockPos.asLong(104, 0, 100)
                )
        );

        assertEquals(
                List.of(
                        new BlockPos(106, 64, 100),
                        new BlockPos(112, 64, 100)
                ),
                trimmed
        );
    }

    private static void setSurfaceColumn(TestServerLevel level, int x, int z, int surfaceY, BlockState state) {
        level.surfaceHeights.put(columnKey(x, z), surfaceY);
        level.blockStates.put(new BlockPos(x, surfaceY, z).asLong(), state);
    }

    private static long columnKey(int x, int z) {
        return BlockPos.asLong(x, 0, z);
    }

    private static boolean hasGhost(RoadPlacementPlan plan, BlockPos pos, net.minecraft.world.level.block.Block expectedBlock) {
        return plan.ghostBlocks().stream().anyMatch(block -> block.pos().equals(pos) && block.state().is(expectedBlock));
    }

    private static RoadPlacementPlan shortBridgePlanFixture() {
        return StructureConstructionManager.createRoadPlacementPlanForTest(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 68, 0),
                        new BlockPos(2, 68, 0),
                        new BlockPos(3, 68, 0),
                        new BlockPos(4, 68, 0),
                        new BlockPos(5, 68, 0),
                        new BlockPos(6, 68, 0),
                        new BlockPos(7, 64, 0)
                ),
                List.of(new RoadPlacementPlan.BridgeRange(1, 6)),
                List.of(new RoadPlacementPlan.BridgeRange(3, 4))
        );
    }

    private static RoadPlacementPlan longBridgePlanFixture() {
        return StructureConstructionManager.createRoadPlacementPlanForTest(
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
    }

    @SuppressWarnings("unchecked")
    private static List<RoadPlacementPlan.BridgeRange> invokeDetectBridgeRanges(ServerLevel level, List<BlockPos> centerPath) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod("detectBridgeRanges", ServerLevel.class, List.class);
            method.setAccessible(true);
            return (List<RoadPlacementPlan.BridgeRange>) method.invoke(null, level, centerPath);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    private static RoadPlacementPlan invokeCreateRoadPlacementPlan(ServerLevel level, List<BlockPos> centerPath) {
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
            BlockPos start = centerPath.get(0);
            BlockPos end = centerPath.get(centerPath.size() - 1);
            return (RoadPlacementPlan) method.invoke(null, level, centerPath, start, start, end, end);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeFilterCoreExcludedPositions(List<BlockPos> positions,
                                                                    List<BlockPos> townCores,
                                                                    List<BlockPos> nationCores) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "filterCoreExcludedPositionsForTest",
                    List.class,
                    List.class,
                    List.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, positions, townCores, nationCores);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeTrimExcludedPathEndpoints(List<BlockPos> centerPath, java.util.Set<Long> excludedColumns) {
        try {
            Method method = StructureConstructionManager.class.getDeclaredMethod(
                    "trimExcludedPathEndpointsForTest",
                    List.class,
                    java.util.Set.class
            );
            method.setAccessible(true);
            return (List<BlockPos>) method.invoke(null, centerPath, excludedColumns);
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
        public int getMinBuildHeight() {
            return 0;
        }

        @Override
        public Holder<Biome> getBiome(BlockPos pos) {
            return biome;
        }
    }
}
