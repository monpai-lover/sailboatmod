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
    void keepsLowerRibbonColumnAsSlabWhileContinuousDiagonalRiseUsesStairsInCenter() {
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
        assertTrue(statesByPos.get(centerPlacement).is(Blocks.STONE_BRICK_STAIRS));
        assertTrue(statesByPos.get(outerPlacement).is(Blocks.STONE_BRICK_SLAB));
    }

    @Test
    void keepsFirstClimbingNodeAsSlabBeforeContinuousRiseTurnsIntoStairs() {
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

        assertTrue(statesByPos.get(firstRise).is(Blocks.STONE_BRICK_SLAB));
        assertTrue(statesByPos.get(secondRise).is(Blocks.STONE_BRICK_STAIRS));
    }

    @Test
    void keepsOuterRibbonColumnsAsSlabsWhileClimbingCoreUsesStairs() {
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

        assertTrue(statesByPos.get(nearPlacement).is(Blocks.STONE_BRICK_STAIRS));
        assertTrue(statesByPos.get(outerPlacement).is(Blocks.STONE_BRICK_SLAB));
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
}
