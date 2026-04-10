package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.SharedConstants;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
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
}
