package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGeometryPlannerSlopeTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
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
    void interpolatesRibbonShoulderHeightAgainstNearestClimbingSegment() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 66, 1),
                new BlockPos(3, 66, 1)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        BlockPos centerColumn = new BlockPos(centerPath.get(2).getX(), 0, centerPath.get(2).getZ());
        BlockPos shoulderColumn = RoadGeometryPlanner.buildRibbonSlice(centerPath, 2).columns().stream()
                .filter(column -> !column.equals(centerColumn))
                .findFirst()
                .orElseThrow();
        int expectedY = RoadGeometryPlanner.interpolatePlacementHeight(
                shoulderColumn.getX(),
                shoulderColumn.getZ(),
                centerPath,
                placementHeights
        );

        List<BlockPos> positions = plan.ghostBlocks().stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();
        assertTrue(positions.contains(new BlockPos(shoulderColumn.getX(), expectedY, shoulderColumn.getZ())));
        assertFalse(positions.contains(new BlockPos(shoulderColumn.getX(), expectedY - 1, shoulderColumn.getZ())));
    }

    @Test
    void keepsLowerRibbonColumnAsSlabWhileContinuousDiagonalRiseKeepsCenterAsSlab() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 1),
                new BlockPos(2, 66, 2),
                new BlockPos(3, 67, 3)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        Map<BlockPos, BlockState> statesByPos = statesByPos(plan);
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        BlockPos centerPlacement = toPlacement(
                new BlockPos(centerPath.get(2).getX(), 0, centerPath.get(2).getZ()),
                centerPath,
                placementHeights
        );
        BlockPos outerPlacement = RoadGeometryPlanner.buildRibbonSlice(centerPath, 2).columns().stream()
                .max(Comparator.comparingInt(column -> distanceSq(column, centerPath.get(2))))
                .map(column -> toPlacement(column, centerPath, placementHeights))
                .orElseThrow();

        assertTrue(statesByPos.containsKey(centerPlacement));
        assertTrue(statesByPos.containsKey(outerPlacement));
        assertTrue(statesByPos.get(centerPlacement).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(outerPlacement).is(Blocks.STONE_BRICK_SLAB));
    }

    @Test
    void keepsEarlyClimbingNodesAsSlabsUnderThreeSegmentSlopeLimit() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 66, 0),
                new BlockPos(3, 67, 0)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        Map<BlockPos, BlockState> statesByPos = statesByPos(plan);
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        BlockPos firstRise = toPlacement(new BlockPos(centerPath.get(1).getX(), 0, centerPath.get(1).getZ()), centerPath, placementHeights);
        BlockPos secondRise = toPlacement(new BlockPos(centerPath.get(2).getX(), 0, centerPath.get(2).getZ()), centerPath, placementHeights);

        assertTrue(statesByPos.containsKey(firstRise));
        assertTrue(statesByPos.containsKey(secondRise));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)));
        assertTrue(statesByPos.get(firstRise).is(Blocks.STONE_BRICK_SLAB) || statesByPos.get(firstRise).is(Blocks.STONE_BRICKS));
        assertTrue(statesByPos.get(secondRise).is(Blocks.STONE_BRICK_SLAB) || statesByPos.get(secondRise).is(Blocks.STONE_BRICKS));
    }

    @Test
    void keepsOuterRibbonColumnsAsSlabsWhileClimbingCoreUsesFullBlocks() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 66, 0),
                new BlockPos(3, 67, 0)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        Map<BlockPos, BlockState> statesByPos = statesByPos(plan);
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        BlockPos centerColumn = new BlockPos(centerPath.get(2).getX(), 0, centerPath.get(2).getZ());
        BlockPos nearPlacement = RoadGeometryPlanner.buildRibbonSlice(centerPath, 2).columns().stream()
                .filter(column -> !column.equals(centerColumn))
                .min(Comparator.comparingInt(column -> distanceSq(column, centerPath.get(2))))
                .map(column -> toPlacement(column, centerPath, placementHeights))
                .orElseThrow();
        BlockPos outerPlacement = RoadGeometryPlanner.buildRibbonSlice(centerPath, 2).columns().stream()
                .max(Comparator.comparingInt(column -> distanceSq(column, centerPath.get(2))))
                .map(column -> toPlacement(column, centerPath, placementHeights))
                .orElseThrow();

        assertTrue(statesByPos.get(nearPlacement).is(Blocks.STONE_BRICKS));
        assertTrue(statesByPos.get(outerPlacement).is(Blocks.STONE_BRICK_SLAB));
    }

    @Test
    void overlappingSlopedTurnBlockKeepsSlabAndFullBlockPaletteWithoutStairs() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 65, 0),
                new BlockPos(2, 66, 1),
                new BlockPos(2, 67, 2)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        Map<BlockPos, BlockState> statesByPos = statesByPos(plan);

        assertFalse(statesByPos.isEmpty());
        assertTrue(statesByPos.values().stream().noneMatch(state -> state.is(Blocks.STONE_BRICK_STAIRS)));
        assertTrue(statesByPos.values().stream().allMatch(state ->
                state.is(Blocks.STONE_BRICK_SLAB) || state.is(Blocks.STONE_BRICKS)));
    }

    @Test
    void uphillSegmentsStayNavigableWithSlabTransitionsUnderShallowSlopeRule() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 66, 0),
                new BlockPos(3, 66, 0)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);

        BlockState state = plan.ghostBlocks().stream()
                .filter(block -> block.pos().equals(new BlockPos(2, placementHeights[2], 0)))
                .findFirst()
                .orElseThrow()
                .state();

        assertTrue(state.is(Blocks.STONE_BRICK_SLAB) || state.is(Blocks.STONE_BRICKS));
        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)));
    }

    @Test
    void bridgeheadRampUsesSlabAndFullBlockTransitionsWithoutStairs() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(2, 68, 0),
                new BlockPos(3, 68, 0)
        );
        int[] placementHeights = new int[] {64, 65, 68, 68};

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                RoadCorridorPlanner.plan(
                        centerPath,
                        List.of(new RoadPlacementPlan.BridgeRange(2, 3)),
                        List.of(new RoadPlacementPlan.BridgeRange(2, 3)),
                        placementHeights
                ),
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        assertTrue(plan.ghostBlocks().stream().noneMatch(block -> block.state().is(Blocks.STONE_BRICK_STAIRS)));
        assertTrue(plan.ghostBlocks().stream().anyMatch(block -> block.state().is(Blocks.STONE_BRICK_SLAB)));
    }

    @Test
    void pierBridgeApproachProfileExtendsRampLengthBeforeIncreasingSlope() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 9),
                index -> index >= 1 && index <= 9,
                index -> index >= 4 && index <= 6,
                index -> 61,
                index -> 63,
                index -> true
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
                centerPath,
                List.of(spanPlan)
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, spanPlan.mode());
        assertEquals(65, heights[1]);
        assertTrue(heights[2] <= 67, () -> "expected shallower rise before main deck: " + java.util.Arrays.toString(heights));
        assertEquals(68, heights[4]);
        assertTrue(heights[8] >= 66, () -> "expected shallower descent from main deck: " + java.util.Arrays.toString(heights));
    }

    @Test
    void bridgeInfluenceColumnsPreserveStructuredTouchdownRampOutsideBridgeRange() {
        List<BlockPos> centerPath = pierBridgeTouchdownPath();
        RoadBridgePlanner.BridgeSpanPlan spanPlan = pierBridgeTouchdownSpan(centerPath);

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
                centerPath,
                List.of(spanPlan)
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, spanPlan.mode());
        assertEquals(heights[2] - 1, heights[1], () -> java.util.Arrays.toString(heights));
        assertEquals(heights[1] - 1, heights[0], () -> java.util.Arrays.toString(heights));
        assertEquals(heights[9] - 1, heights[10], () -> java.util.Arrays.toString(heights));
        assertEquals(heights[10] - 1, heights[11], () -> java.util.Arrays.toString(heights));
    }

    @Test
    void bridgeTouchdownProfileDoesNotCollapseIntoTerrainLockedColumns() {
        List<BlockPos> centerPath = pierBridgeTouchdownPath();
        RoadBridgePlanner.BridgeSpanPlan spanPlan = pierBridgeTouchdownSpan(centerPath);

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
                centerPath,
                List.of(spanPlan)
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, spanPlan.mode());
        assertTrue(heights[1] > centerPath.get(1).getY() + 1, () -> java.util.Arrays.toString(heights));
        assertTrue(heights[10] > centerPath.get(10).getY() + 1, () -> java.util.Arrays.toString(heights));
    }

    @Test
    void longPierBridgeApproachUsesStructuredExtremeGentleRampPlateaus() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0),
                new BlockPos(11, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(13, 64, 0),
                new BlockPos(14, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 13),
                index -> index >= 1 && index <= 13,
                index -> index >= 6 && index <= 8,
                index -> 61,
                index -> 63,
                index -> true
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
                centerPath,
                List.of(spanPlan)
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, spanPlan.mode());
        assertEquals(65, heights[1], () -> java.util.Arrays.toString(heights));
        assertEquals(65, heights[2], () -> "expected first long-ramp plateau before climbing: " + java.util.Arrays.toString(heights));
        assertEquals(65, heights[3], () -> "expected bridgehead platform to stay flat before the slab climb starts: " + java.util.Arrays.toString(heights));
        assertEquals(66, heights[4], () -> "expected the first rise to start after the full bridgehead platform: " + java.util.Arrays.toString(heights));
        assertEquals(67, heights[5], () -> java.util.Arrays.toString(heights));
        assertEquals(68, heights[6], () -> java.util.Arrays.toString(heights));
        assertEquals(68, heights[7], () -> "expected main deck to start later after a longer ramp: " + java.util.Arrays.toString(heights));
        assertEquals(68, heights[8], () -> java.util.Arrays.toString(heights));
        assertEquals(67, heights[9], () -> java.util.Arrays.toString(heights));
        assertEquals(66, heights[10], () -> java.util.Arrays.toString(heights));
        assertEquals(65, heights[11], () -> java.util.Arrays.toString(heights));
        assertEquals(65, heights[12], () -> "expected symmetric delayed touchdown on the far side: " + java.util.Arrays.toString(heights));
    }

    @Test
    void bridgeheadPlatformStaysFullBlockWhileWholeRampRunUsesSlabs() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0),
                new BlockPos(11, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(13, 64, 0),
                new BlockPos(14, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 13),
                index -> index >= 1 && index <= 13,
                index -> index >= 6 && index <= 8,
                index -> 61,
                index -> 63,
                index -> true
        );
        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(centerPath, List.of(spanPlan));
        RoadCorridorPlan corridorPlan = RoadCorridorPlanner.plan(centerPath, List.of(spanPlan), heights);
        RoadGeometryPlanner.RoadGeometryPlan geometryPlan = RoadGeometryPlanner.plan(
                corridorPlan,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        Map<BlockPos, BlockState> statesByPos = statesByPos(geometryPlan);

        assertEquals(65, heights[1], () -> java.util.Arrays.toString(heights));
        assertEquals(65, heights[2], () -> java.util.Arrays.toString(heights));
        assertEquals(65, heights[3], () -> java.util.Arrays.toString(heights));
        assertTrue(statesByPos.get(new BlockPos(2, 65, 0)).is(Blocks.STONE_BRICKS));
        assertTrue(statesByPos.get(new BlockPos(4, 66, 0)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(new BlockPos(5, 67, 0)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(new BlockPos(10, 67, 0)).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(new BlockPos(12, 65, 0)).is(Blocks.STONE_BRICKS));
    }

    @Test
    void pierBridgeKeepsEntireWaterRunAtLeastFiveBlocksAboveWater() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(3, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(5, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(7, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(9, 64, 0),
                new BlockPos(10, 64, 0),
                new BlockPos(11, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(13, 64, 0),
                new BlockPos(14, 64, 0)
        );

        RoadBridgePlanner.BridgeSpanPlan spanPlan = RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(1, 13),
                index -> index >= 1 && index <= 13,
                index -> index >= 6 && index <= 8,
                index -> index >= 2 && index <= 12 ? 61 : 63,
                index -> index >= 2 && index <= 12 ? 63 : 0,
                index -> true
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfileFromSpanPlans(
                centerPath,
                List.of(spanPlan)
        );

        assertEquals(RoadBridgePlanner.BridgeMode.PIER_BRIDGE, spanPlan.mode());
        for (int i = 2; i <= 12; i++) {
            int index = i;
            assertTrue(
                    heights[i] >= 68,
                    () -> "expected full water run to stay at water + 5, index=" + index + " heights="
                            + java.util.Arrays.toString(heights)
            );
        }
    }

    @Test
    void placementHeightProfileNeverChangesMoreThanOneLevelAcrossThreeSegments() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 67, 0),
                new BlockPos(3, 67, 0),
                new BlockPos(4, 70, 0),
                new BlockPos(5, 70, 0),
                new BlockPos(6, 73, 0)
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);

        for (int i = 0; i + 3 < heights.length; i++) {
            assertTrue(
                    Math.abs(heights[i + 3] - heights[i]) <= 1,
                    () -> "expected <= 1 level change across 3 segments: " + java.util.Arrays.toString(heights)
            );
        }
    }

    @Test
    void turningSlopeSegmentsPreferSlabsInsteadOfFullBlocks() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(1, 66, 1),
                new BlockPos(1, 67, 2)
        );

        int[] heights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);
        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );

        BlockState turningState = plan.ghostBlocks().stream()
                .filter(block -> block.pos().equals(new BlockPos(1, heights[1], 0)))
                .findFirst()
                .orElseThrow()
                .state();

        assertTrue(turningState.is(Blocks.STONE_BRICK_SLAB), turningState.toString());
    }

    @Test
    void diagonalContinuousRisePrefersSlabToAvoidAmbiguousStairFacing() {
        List<BlockPos> centerPath = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 65, 1),
                new BlockPos(2, 66, 2),
                new BlockPos(3, 67, 3)
        );

        RoadGeometryPlanner.RoadGeometryPlan plan = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        int[] placementHeights = RoadGeometryPlanner.buildPlacementHeightProfile(centerPath);

        BlockState state = plan.ghostBlocks().stream()
                .filter(block -> block.pos().equals(new BlockPos(2, placementHeights[2], 2)))
                .findFirst()
                .orElseThrow()
                .state();

        assertTrue(state.is(Blocks.STONE_BRICK_SLAB));
    }

    private static Map<BlockPos, BlockState> statesByPos(RoadGeometryPlanner.RoadGeometryPlan plan) {
        Map<BlockPos, BlockState> statesByPos = new LinkedHashMap<>();
        for (RoadGeometryPlanner.GhostRoadBlock ghostBlock : plan.ghostBlocks()) {
            statesByPos.put(ghostBlock.pos(), ghostBlock.state());
        }
        return statesByPos;
    }

    private static int distanceSq(BlockPos column, BlockPos center) {
        int dx = column.getX() - center.getX();
        int dz = column.getZ() - center.getZ();
        return (dx * dx) + (dz * dz);
    }

    private static BlockPos toPlacement(BlockPos column, List<BlockPos> centerPath, int[] placementHeights) {
        int y = RoadGeometryPlanner.interpolatePlacementHeight(column.getX(), column.getZ(), centerPath, placementHeights);
        return new BlockPos(column.getX(), y, column.getZ());
    }

    private static boolean isStairSegment(int[] placementHeights, int index) {
        if (placementHeights.length < 3) {
            return false;
        }
        int current = placementHeights[index];
        int previous = index > 0 ? placementHeights[index - 1] : current;
        int next = index + 1 < placementHeights.length ? placementHeights[index + 1] : current;
        int riseIn = current - previous;
        int riseOut = next - current;
        return riseIn != 0 && riseOut != 0 && Integer.signum(riseIn) == Integer.signum(riseOut);
    }

    private static List<BlockPos> pierBridgeTouchdownPath() {
        return List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(1, 67, 0),
                new BlockPos(2, 70, 0),
                new BlockPos(3, 70, 0),
                new BlockPos(4, 70, 0),
                new BlockPos(5, 70, 0),
                new BlockPos(6, 70, 0),
                new BlockPos(7, 70, 0),
                new BlockPos(8, 70, 0),
                new BlockPos(9, 70, 0),
                new BlockPos(10, 67, 0),
                new BlockPos(11, 64, 0)
        );
    }

    private static RoadBridgePlanner.BridgeSpanPlan pierBridgeTouchdownSpan(List<BlockPos> centerPath) {
        return RoadBridgePlanner.planBridgeSpanForTest(
                centerPath,
                new RoadPlacementPlan.BridgeRange(2, 9),
                index -> index >= 2 && index <= 9,
                index -> index >= 5 && index <= 6,
                index -> 40,
                index -> 63,
                index -> true
        );
    }

}
