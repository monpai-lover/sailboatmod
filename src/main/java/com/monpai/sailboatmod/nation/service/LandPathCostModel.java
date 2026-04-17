package com.monpai.sailboatmod.nation.service;

public final class LandPathCostModel {
    private LandPathCostModel() {
    }

    public static double moveCost(int orthoOrDiagCost,
                                  int elevationDelta,
                                  int stabilityCost,
                                  int nearWaterCost,
                                  double deviationCost) {
        int safeBase = Math.max(1, orthoOrDiagCost);
        int safeElevation = Math.max(0, elevationDelta);
        int safeStability = Math.max(0, stabilityCost);
        int safeNearWater = Math.max(0, nearWaterCost);
        double safeDeviation = Math.max(0.0D, deviationCost);
        return safeBase + (safeElevation * 2.0D) + safeStability + safeNearWater + safeDeviation;
    }
}
