package com.monpai.sailboatmod.construction;

public record BuilderHammerChargePlan(boolean success, long walletSpent, long treasurySpent, long shortfall) {
    public static BuilderHammerChargePlan allocate(long totalCost, long walletAvailable, long treasuryAvailable) {
        long safeCost = Math.max(0L, totalCost);
        long safeWallet = Math.max(0L, walletAvailable);
        long safeTreasury = Math.max(0L, treasuryAvailable);
        if (safeCost <= 0L) {
            return new BuilderHammerChargePlan(true, 0L, 0L, 0L);
        }

        long walletSpent = Math.min(safeCost, safeWallet);
        long treasurySpent = Math.min(safeCost - walletSpent, safeTreasury);
        long shortfall = safeCost - walletSpent - treasurySpent;
        if (shortfall > 0L) {
            return new BuilderHammerChargePlan(false, 0L, 0L, shortfall);
        }
        return new BuilderHammerChargePlan(true, walletSpent, treasurySpent, 0L);
    }
}
