package com.monpai.sailboatmod.construction;

import com.monpai.sailboatmod.block.NationCoreBlock;
import com.monpai.sailboatmod.block.TownCoreBlock;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructionStepSatisfactionServiceTest {
    @BeforeAll
    static void bootstrapMinecraftRegistries() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void naturalLeavesAreRetryableCleanupNotSatisfiedRoadDeck() {
        ConstructionStepSatisfactionService.StepDecision decision =
                ConstructionStepSatisfactionService.decideForTest(
                        Blocks.OAK_LEAVES.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                        new BlockPos(0, 64, 0),
                        ConstructionStepSatisfactionService.StepKind.ROAD_DECK
                );

        assertEquals(ConstructionStepSatisfactionService.StepDecision.RETRYABLE, decision);
    }

    @Test
    void equivalentStoneBrickDeckCountsAsSatisfied() {
        ConstructionStepSatisfactionService.StepDecision decision =
                ConstructionStepSatisfactionService.decideForTest(
                        Blocks.STONE_BRICKS.defaultBlockState(),
                        Blocks.STONE_BRICK_SLAB.defaultBlockState(),
                        new BlockPos(0, 64, 0),
                        ConstructionStepSatisfactionService.StepKind.ROAD_DECK
                );

        assertEquals(ConstructionStepSatisfactionService.StepDecision.SATISFIED, decision);
    }

    @Test
    void townAndNationCoreClassesAreProtectedFromConstructionReplacement() {
        assertEquals(
                true,
                ConstructionStateMatchers.isProtectedCoreBlockClassForTest(TownCoreBlock.class)
        );
        assertEquals(
                true,
                ConstructionStateMatchers.isProtectedCoreBlockClassForTest(NationCoreBlock.class)
        );
    }
}
