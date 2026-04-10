package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    void createsFullWidthGhostRoadForTurns() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0), new BlockPos(1, 64, 1)),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        List<BlockPos> positions = plan.ghostBlocks().stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();
        BlockPos interiorCorner = new BlockPos(1, 65, 0);
        assertTrue(positions.contains(interiorCorner.north()));
        assertTrue(positions.contains(interiorCorner.south()));
        assertTrue(positions.contains(interiorCorner.east()));
        assertTrue(positions.contains(interiorCorner.west()));
        assertTrue(positions.contains(interiorCorner.north(2)));
        assertTrue(positions.contains(interiorCorner.east(2)));
        assertFalse(positions.contains(new BlockPos(4, 65, 0)));
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

        assertEquals(firstPositions, secondPositions);
        assertTrue(firstPositions.contains(new BlockPos(0, 65, 0)));
        assertTrue(firstPositions.contains(new BlockPos(0, 65, -2)));
        assertTrue(firstPositions.contains(new BlockPos(3, 65, 0)));
        assertTrue(firstPositions.contains(new BlockPos(-1, 65, 2)));
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
                null,
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
    void plannerGeometryMatchesCurrentRuntimeRoadSliceSemantics() {
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

        assertEquals(runtimeRoadPlacementPositions(centerPath), plannerPositions);
    }

    @Test
    void smoothsSteepCenterlinePlacementHeightInsteadOfJumpingWholeCliffs() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 67, 0),
                        new BlockPos(3, 67, 0)
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        List<BlockPos> positions = plan.ghostBlocks().stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();

        assertTrue(positions.contains(new BlockPos(2, 66, 0)));
        assertFalse(positions.contains(new BlockPos(2, 68, 0)));
    }

    @Test
    void interpolatesTurnShoulderHeightAgainstNearestClimbingSegment() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 64, 0),
                        new BlockPos(2, 66, 1),
                        new BlockPos(3, 66, 1)
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        List<BlockPos> positions = plan.ghostBlocks().stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();

        assertTrue(positions.contains(new BlockPos(2, 66, 0)));
        assertFalse(positions.contains(new BlockPos(2, 65, 0)));
    }

    @Test
    void keepsLowerShoulderAsSlabWhileContinuousDiagonalRiseUsesStairsInCenter() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 65, 1),
                        new BlockPos(2, 66, 2),
                        new BlockPos(3, 67, 3)
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : plan.ghostBlocks()) {
            statesByPos.put(ghostBlock.pos(), ghostBlock.state());
        }

        assertTrue(statesByPos.containsKey(new BlockPos(2, 66, 2)));
        assertTrue(statesByPos.containsKey(new BlockPos(2, 65, 0)));
        assertTrue(statesByPos.get(new BlockPos(2, 66, 2)).is(Blocks.STONE_BRICK_STAIRS));
        assertTrue(statesByPos.get(new BlockPos(2, 65, 0)).is(Blocks.STONE_BRICK_SLAB));
    }

    @Test
    void keepsFirstClimbingNodeAsSlabBeforeContinuousRiseTurnsIntoStairs() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 65, 0),
                        new BlockPos(2, 66, 0),
                        new BlockPos(3, 67, 0)
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : plan.ghostBlocks()) {
            statesByPos.put(ghostBlock.pos(), ghostBlock.state());
        }

        assertTrue(statesByPos.get(new BlockPos(1, 65, 0)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(new BlockPos(2, 66, 0)).is(Blocks.STONE_BRICK_STAIRS));
    }

    @Test
    void keepsOuterShoulderAsSlabWhileClimbingCoreUsesStairs() {
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                List.of(
                        new BlockPos(0, 64, 0),
                        new BlockPos(1, 65, 0),
                        new BlockPos(2, 66, 0),
                        new BlockPos(3, 67, 0)
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : plan.ghostBlocks()) {
            statesByPos.put(ghostBlock.pos(), ghostBlock.state());
        }

        assertTrue(statesByPos.get(new BlockPos(2, 66, -1)).is(Blocks.STONE_BRICK_STAIRS));
        assertTrue(statesByPos.get(new BlockPos(2, 66, -2)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(new BlockPos(2, 66, 2)).is(Blocks.STONE_BRICK_SLAB));
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

    private static Set<BlockPos> runtimeRoadPlacementPositions(List<BlockPos> centerPath) {
        try {
            Method collectRoadSlicePositions = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("collectRoadSlicePositions", List.class, int.class);
            collectRoadSlicePositions.setAccessible(true);

            LinkedHashSet<BlockPos> positions = new LinkedHashSet<>();
            for (int i = 0; i < centerPath.size(); i++) {
                @SuppressWarnings("unchecked")
                Set<BlockPos> slice = (Set<BlockPos>) collectRoadSlicePositions.invoke(null, centerPath, i);
                positions.addAll(slice);
            }
            return positions;
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to read runtime road-slice semantics", ex);
        }
    }
}
