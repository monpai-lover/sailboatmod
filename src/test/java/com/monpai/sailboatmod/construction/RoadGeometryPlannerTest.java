package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.nation.service.StructureConstructionManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGeometryPlannerTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void planIncludesWidenedRibbonColumnsForTurns() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1)
        );
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Set<BlockPos> planned = plan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<BlockPos> expectedTurnSlice = ribbonSlicePlacements(centerPath, 1);
        assertTrue(planned.containsAll(expectedTurnSlice));
        assertTrue(expectedTurnSlice.contains(new BlockPos(-3, 65, 4)));
        assertTrue(expectedTurnSlice.contains(new BlockPos(4, 65, -3)));
    }

    @Test
    void createsStableUniqueBuildStepOrdering() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1),
                new BlockPos(1, 64, 2)
        );

        RoadGeometryPlanner.RoadGeometryPlan first = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadGeometryPlanner.RoadGeometryPlan second = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        List<BlockPos> firstPositions = first.buildSteps().stream().map(RoadGeometryPlanner.RoadBuildStep::pos).toList();
        List<BlockPos> secondPositions = second.buildSteps().stream().map(RoadGeometryPlanner.RoadBuildStep::pos).toList();

        Set<BlockPos> expectedRibbonPlacements = ribbonPlacementPositions(centerPath);
        assertEquals(firstPositions, secondPositions);
        assertEquals(expectedRibbonPlacements.size(), firstPositions.size());
        assertTrue(firstPositions.containsAll(expectedRibbonPlacements));
        assertEquals(new LinkedHashSet<>(firstPositions).size(), firstPositions.size());

        List<Integer> firstOrders = first.buildSteps().stream().map(RoadGeometryPlanner.RoadBuildStep::order).toList();
        assertEquals(java.util.stream.IntStream.range(0, firstOrders.size()).boxed().toList(), firstOrders);
    }

    @Test
    void roadPlacementPlanRejectsNullCenterPathList() {
        assertThrows(NullPointerException.class, () -> new RoadPlacementPlan(
                null,
                new BlockPos(9, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(11, 64, 10),
                new BlockPos(12, 64, 10),
                List.of(),
                List.of(),
                List.of(),
                new BlockPos(10, 65, 10),
                new BlockPos(11, 65, 10),
                new BlockPos(10, 65, 10)
        ));
    }

    @Test
    void roadPlacementPlanRejectsNullCenterPathElements() {
        List<BlockPos> centerPath = new ArrayList<>();
        centerPath.add(new BlockPos(10, 64, 10));
        centerPath.add(null);
        centerPath.add(new BlockPos(12, 64, 10));

        assertThrows(NullPointerException.class, () -> new RoadPlacementPlan(
                centerPath,
                new BlockPos(9, 64, 10),
                new BlockPos(10, 64, 10),
                new BlockPos(11, 64, 10),
                new BlockPos(12, 64, 10),
                List.of(),
                List.of(),
                List.of(),
                new BlockPos(10, 65, 10),
                new BlockPos(11, 65, 10),
                new BlockPos(10, 65, 10)
        ));
    }

    @Test
    void plannerRejectsNullCenterPathElements() {
        List<BlockPos> centerPath = new ArrayList<>();
        centerPath.add(new BlockPos(10, 64, 10));
        centerPath.add(null);
        centerPath.add(new BlockPos(12, 64, 10));

        assertThrows(NullPointerException.class, () -> RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        ));
    }

    @Test
    void plannerRejectsNullCenterPathList() {
        assertThrows(NullPointerException.class, () -> RoadGeometryPlanner.plan(
                (List<BlockPos>) null,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        ));
    }

    @Test
    void plannerRejectsNullBlockStateSupplier() {
        assertThrows(NullPointerException.class, () -> RoadGeometryPlanner.plan(
                List.of(new BlockPos(10, 64, 10)),
                null
        ));
    }

    @Test
    void plannerRejectsNullBlockStateResults() {
        assertThrows(NullPointerException.class, () -> RoadGeometryPlanner.plan(
                List.of(new BlockPos(10, 64, 10)),
                pos -> null
        ));
    }

    @Test
    void ghostRoadBlockRejectsNullPosition() {
        assertThrows(NullPointerException.class, () -> new RoadGeometryPlanner.GhostRoadBlock(
                null,
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        ));
    }

    @Test
    void roadBuildStepRejectsNullPosition() {
        assertThrows(NullPointerException.class, () -> new RoadGeometryPlanner.RoadBuildStep(
                0,
                null,
                Blocks.STONE_BRICK_SLAB.defaultBlockState()
        ));
    }

    @Test
    void plannerGeometryMatchesRibbonSliceSemantics() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1),
                new BlockPos(3, 64, 1)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Set<BlockPos> plannerPositions = plan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(ribbonPlacementPositions(centerPath), plannerPositions);
    }

    @Test
    void slicePositionsUseRibbonColumnsWithInterpolatedHeights() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(1, 66, 1),
                new BlockPos(2, 66, 1)
        );
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);

        for (int i = 0; i < centerPath.size(); i++) {
            Set<BlockPos> actual = new LinkedHashSet<>(RoadGeometryPlanner.slicePositions(centerPath, i));
            Set<BlockPos> expected = ribbonSlicePlacements(centerPath, i);
            assertEquals(expected, actual);

            for (BlockPos placed : actual) {
                int expectedY = RoadGeometryPlanner.interpolatePlacementHeight(
                        placed.getX(),
                        placed.getZ(),
                        centerPath,
                        placementHeights
                );
                assertEquals(expectedY, placed.getY());
            }
        }
    }

    @Test
    void raisesArchedBridgeMidpointWhileKeepingEndsAtBaselineDeckHeight() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0)
        );
        List<RoadBridgePlanner.BridgeProfile> bridgeProfiles = List.of(
                new RoadBridgePlanner.BridgeProfile(0, 5, RoadBridgePlanner.BridgeKind.ARCHED)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.SPRUCE_SLAB.defaultBlockState(),
                bridgeProfiles
        );

        Set<BlockPos> positions = plan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertTrue(positions.contains(new BlockPos(0, 65, 0)));
        assertTrue(positions.contains(new BlockPos(5, 65, 0)));
        assertTrue(positions.contains(new BlockPos(2, 68, 0)));
        assertTrue(positions.contains(new BlockPos(3, 68, 0)));
    }

    @Test
    void bridgeHeightProfileRaisesWaterSpanAboveApproachTerrain() {
        RoadBridgePlanner.BridgeProfile profile = RoadBridgePlanner.buildNavigableBridgeProfile(1, 2, 64);

        int[] heights = RoadGeometryPlanner.applyNavigableBridgeProfileForTest(new int[] {64, 64, 64, 64}, profile);

        assertTrue(heights[1] >= 69);
        assertTrue(heights[2] >= 69);
    }

    @Test
    void placementHeightProfileDoesNotSinkBelowSteepCenterPathDeck() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 70, 0),
                new BlockPos(4, 72, 0)
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);

        assertEquals(65, heights[0]);
        assertEquals(67, heights[1]);
        assertEquals(69, heights[2]);
        assertEquals(71, heights[3]);
        assertEquals(73, heights[4]);
    }

    @Test
    void corridorPlanUsesExplicitSliceSurfaceFootprints() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 66, 0),
                new BlockPos(2, 68, 0)
        );
        List<BlockPos> slice0 = List.of(new BlockPos(0, 65, 0), new BlockPos(0, 65, 1));
        List<BlockPos> slice1 = List.of(new BlockPos(1, 67, 0), new BlockPos(1, 67, 1));
        List<BlockPos> slice2 = List.of(new BlockPos(2, 69, 0), new BlockPos(2, 69, 1));
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                centerPath,
                List.of(
                        new RoadCorridorPlan.CorridorSlice(0, new BlockPos(0, 65, 0), RoadCorridorPlan.SegmentKind.LAND_APPROACH, slice0, List.of(), List.of(), List.of(), List.of()),
                        new RoadCorridorPlan.CorridorSlice(1, new BlockPos(1, 67, 0), RoadCorridorPlan.SegmentKind.LAND_APPROACH, slice1, List.of(), List.of(), List.of(), List.of()),
                        new RoadCorridorPlan.CorridorSlice(2, new BlockPos(2, 69, 0), RoadCorridorPlan.SegmentKind.LAND_APPROACH, slice2, List.of(), List.of(), List.of(), List.of())
                ),
                null,
                true
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Set<BlockPos> positions = plan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertEquals(Set.copyOf(List.of(
                new BlockPos(0, 65, 0),
                new BlockPos(0, 65, 1),
                new BlockPos(1, 67, 0),
                new BlockPos(1, 67, 1),
                new BlockPos(2, 69, 0),
                new BlockPos(2, 69, 1)
        )), positions);
    }

    @Test
    void corridorPlanReturnsEmptyGeometryWhenInvalid() {
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0));
        RoadCorridorPlan corridorPlan = new RoadCorridorPlan(
                centerPath,
                List.of(
                        new RoadCorridorPlan.CorridorSlice(0, new BlockPos(0, 65, 0), RoadCorridorPlan.SegmentKind.NAVIGABLE_MAIN_SPAN, List.of(new BlockPos(0, 65, 0)), List.of(), List.of(), List.of(), List.of())
                ),
                null,
                false
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> Blocks.SPRUCE_SLAB.defaultBlockState()
        );

        assertTrue(plan.ghostBlocks().isEmpty());
        assertTrue(plan.buildSteps().isEmpty());
    }

    @Test
    void productionCorridorFromPlacementBuilderKeepsBridgeTransitionSlicesClosed() {
        RoadPlacementPlan plan = buildProductionRiverPlanForTest();
        RoadCorridorPlan corridorPlan = plan.corridorPlan();

        int closureIndex = -1;
        for (int i = 1; i < corridorPlan.slices().size(); i++) {
            if (!Collections.disjoint(
                    corridorPlan.slices().get(i - 1).surfacePositions(),
                    corridorPlan.slices().get(i).surfacePositions()
            ) && (corridorPlan.slices().get(i - 1).segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH
                    || corridorPlan.slices().get(i).segmentKind() != RoadCorridorPlan.SegmentKind.LAND_APPROACH)) {
                closureIndex = i;
                break;
            }
        }

        assertTrue(closureIndex >= 0, summarizeCorridorPlan(corridorPlan));
        assertFalse(Collections.disjoint(
                new LinkedHashSet<>(RoadGeometryPlanner.slicePositions(corridorPlan, closureIndex - 1)),
                new LinkedHashSet<>(RoadGeometryPlanner.slicePositions(corridorPlan, closureIndex))
        ), summarizeCorridorPlan(corridorPlan));
        assertFalse(RoadGeometryPlanner.sliceGhostBlocks(
                corridorPlan,
                closureIndex,
                pos -> Blocks.SPRUCE_SLAB.defaultBlockState()
        ).isEmpty());
    }

    @Test
    void preservesSlopedBridgeEndpointsWhileRaisingArchedInterior() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 65, 0),
                new BlockPos(3, 65, 0),
                new BlockPos(4, 66, 0),
                new BlockPos(5, 66, 0)
        );
        List<RoadBridgePlanner.BridgeProfile> bridgeProfiles = List.of(
                new RoadBridgePlanner.BridgeProfile(0, 5, RoadBridgePlanner.BridgeKind.ARCHED)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.SPRUCE_SLAB.defaultBlockState(),
                bridgeProfiles
        );

        Set<BlockPos> positions = plan.ghostBlocks().stream()
                .map(RoadGeometryPlanner.GhostRoadBlock::pos)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        assertTrue(positions.contains(new BlockPos(0, 65, 0)));
        assertTrue(positions.contains(new BlockPos(5, 67, 0)));
        assertTrue(positions.contains(new BlockPos(2, 69, 0)));
        assertTrue(positions.contains(new BlockPos(3, 69, 0)));
    }

    @Test
    void roadPlacementPlanConstructorMatchesSharedContractOrder() {
        List<BlockPos> centerPath = List.of(new BlockPos(10, 64, 10), new BlockPos(11, 64, 10));
        BlockPos sourceInternalAnchor = new BlockPos(9, 64, 10);
        BlockPos sourceBoundaryAnchor = new BlockPos(10, 64, 10);
        BlockPos targetBoundaryAnchor = new BlockPos(11, 64, 10);
        BlockPos targetInternalAnchor = new BlockPos(12, 64, 10);
        List<RoadGeometryPlanner.GhostRoadBlock> ghostBlocks = List.of(
                new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(10, 65, 10), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<RoadGeometryPlanner.RoadBuildStep> buildSteps = List.of(
                new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(10, 65, 10), Blocks.STONE_BRICK_SLAB.defaultBlockState())
        );
        List<RoadPlacementPlan.BridgeRange> bridgeRanges = List.of(new RoadPlacementPlan.BridgeRange(0, 1));
        BlockPos startHighlightPos = new BlockPos(10, 65, 10);
        BlockPos endHighlightPos = new BlockPos(11, 65, 10);
        BlockPos focusPos = new BlockPos(10, 65, 10);

        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                sourceInternalAnchor,
                sourceBoundaryAnchor,
                targetBoundaryAnchor,
                targetInternalAnchor,
                ghostBlocks,
                buildSteps,
                bridgeRanges,
                startHighlightPos,
                endHighlightPos,
                focusPos
        );

        assertEquals(centerPath, plan.centerPath());
        assertEquals(sourceInternalAnchor, plan.sourceInternalAnchor());
        assertEquals(sourceBoundaryAnchor, plan.sourceBoundaryAnchor());
        assertEquals(targetBoundaryAnchor, plan.targetBoundaryAnchor());
        assertEquals(targetInternalAnchor, plan.targetInternalAnchor());
        assertEquals(ghostBlocks, plan.ghostBlocks());
        assertEquals(buildSteps, plan.buildSteps());
        assertEquals(bridgeRanges, plan.bridgeRanges());
        assertEquals(startHighlightPos, plan.startHighlightPos());
        assertEquals(endHighlightPos, plan.endHighlightPos());
        assertEquals(focusPos, plan.focusPos());
    }

    private static Set<BlockPos> ribbonSlicePlacements(List<BlockPos> centerPath, int index) {
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        LinkedHashSet<BlockPos> placements = new LinkedHashSet<>();
        for (BlockPos column : RoadGeometryPlanner.buildRibbonSlice(centerPath, index).columns()) {
            int y = RoadGeometryPlanner.interpolatePlacementHeight(column.getX(), column.getZ(), centerPath, placementHeights);
            placements.add(new BlockPos(column.getX(), y, column.getZ()));
        }
        return placements;
    }

    private static Set<BlockPos> ribbonPlacementPositions(List<BlockPos> centerPath) {
        LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
        for (int i = 0; i < centerPath.size(); i++) {
            positions.addAll(ribbonSlicePlacements(centerPath, i));
        }
        return positions;
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
