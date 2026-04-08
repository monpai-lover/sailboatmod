package com.monpai.sailboatmod.construction;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuilderHammerSupportTest {
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
}
