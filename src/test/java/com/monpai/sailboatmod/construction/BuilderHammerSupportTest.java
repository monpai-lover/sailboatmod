package com.monpai.sailboatmod.construction;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderHammerSupportTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void allocatesWalletBeforeTreasury() {
        BuilderHammerChargePlan plan = BuilderHammerChargePlan.allocate(48L, 20L, 100L);

        assertTrue(plan.success());
        assertEquals(20L, plan.walletSpent());
        assertEquals(28L, plan.treasurySpent());
    }

    @Test
    void failsWhenCombinedFundsAreInsufficient() {
        BuilderHammerChargePlan plan = BuilderHammerChargePlan.allocate(48L, 10L, 12L);

        assertFalse(plan.success());
        assertEquals(0L, plan.walletSpent());
        assertEquals(0L, plan.treasurySpent());
        assertEquals(26L, plan.shortfall());
    }

    @Test
    void capsQueuedHammerCredits() {
        BuilderHammerCreditState state = BuilderHammerCreditState.of(4, 5);

        BuilderHammerCreditState updated = state.enqueue();

        assertTrue(updated.accepted());
        assertEquals(5, updated.queuedCredits());
        assertFalse(updated.enqueue().accepted());
        assertEquals(5, updated.enqueue().queuedCredits());
    }

    @Test
    void clipsRoadGhostBlocksToNearbyWindow() {
        List<BlockPos> positions = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(16, 64, 0)
        );

        List<BlockPos> clipped = RuntimeRoadGhostWindow.clip(positions, new BlockPos(9, 64, 0), 5);

        assertEquals(List.of(
                new BlockPos(4, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(12, 64, 0)
        ), clipped);
    }

    @Test
    void roadRuntimeProgressUsesPlacedBuildStepsAndShrinksGhosts() {
        RoadPlacementPlan plan = new RoadPlacementPlan(
                List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0)),
                new BlockPos(-1, 64, 0),
                new BlockPos(0, 64, 0),
                new BlockPos(1, 64, 0),
                new BlockPos(2, 64, 0),
                List.of(
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(0, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.GhostRoadBlock(new BlockPos(1, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(
                        new RoadGeometryPlanner.RoadBuildStep(0, new BlockPos(0, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(1, new BlockPos(0, 65, 1), Blocks.STONE_BRICK_SLAB.defaultBlockState()),
                        new RoadGeometryPlanner.RoadBuildStep(2, new BlockPos(1, 65, 0), Blocks.STONE_BRICK_SLAB.defaultBlockState())
                ),
                List.of(),
                new BlockPos(0, 65, 0),
                new BlockPos(1, 65, 0),
                new BlockPos(0, 65, 0)
        );

        List<BlockPos> remaining = invokeRemainingRoadGhostPositions(plan, 1);

        assertEquals(List.of(new BlockPos(0, 65, 1), new BlockPos(1, 65, 0)), remaining);
        assertEquals(33, invokeRoadProgressPercent(plan, 1));
    }

    @Test
    void roadHammerConsumesWholeNextRoadBuildBatch() {
        List<BlockPos> centerPath = List.of(new BlockPos(0, 64, 0), new BlockPos(1, 64, 0));
        RoadGeometryPlanner.RoadGeometryPlan geometry = RoadGeometryPlanner.plan(
                centerPath,
                pos -> Blocks.STONE_BRICK_SLAB.defaultBlockState()
        );
        RoadPlacementPlan plan = new RoadPlacementPlan(
                centerPath,
                new BlockPos(-1, 64, 0),
                centerPath.get(0),
                centerPath.get(centerPath.size() - 1),
                new BlockPos(2, 64, 0),
                geometry.ghostBlocks(),
                geometry.buildSteps(),
                List.of(),
                centerPath.get(0).above(),
                centerPath.get(centerPath.size() - 1).above(),
                centerPath.get(0).above()
        );

        assertEquals(3, invokeNextRoadBuildBatchSize(plan, 0));
        assertEquals(3, invokeNextRoadBuildBatchSize(plan, 3));
    }

    @SuppressWarnings("unchecked")
    private static List<BlockPos> invokeRemainingRoadGhostPositions(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("remainingRoadGhostBlocks", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            List<RoadGeometryPlanner.GhostRoadBlock> remaining = (List<RoadGeometryPlanner.GhostRoadBlock>) method.invoke(null, plan, placedStepCount);
            return remaining.stream().map(RoadGeometryPlanner.GhostRoadBlock::pos).toList();
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect remaining road ghost projection", ex);
        }
    }

    private static int invokeRoadProgressPercent(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("roadProgressPercent", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, placedStepCount);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road progress calculation", ex);
        }
    }

    private static int invokeNextRoadBuildBatchSize(RoadPlacementPlan plan, int placedStepCount) {
        try {
            Method method = Class
                    .forName("com.monpai.sailboatmod.nation.service.StructureConstructionManager")
                    .getDeclaredMethod("nextRoadBuildBatchSize", RoadPlacementPlan.class, int.class);
            method.setAccessible(true);
            return (int) method.invoke(null, plan, placedStepCount);
        } catch (ReflectiveOperationException ex) {
            throw new AssertionError("Unable to inspect road hammer batch sizing", ex);
        }
    }
}
