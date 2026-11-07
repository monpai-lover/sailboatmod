package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Map;

/** Compatibility gate for the isolated native cruise-preview search. */
public final class CruiseTrimCompatibility {
    public static final Map<String, String> PINNED_CLASSES = Map.of(
            "FixedWingCruise", "7fa9625ec1965e730750bbf6eebcdf45ec998c3ff12559ff81d5694221c69d3d",
            "FixedWingLevelTrim", "5f4c4b31768607e6d3876b5a309d71b077fc07af25938e353d3ef2aff4fd87b0",
            "FixedWingPerformance", "7ea1771e78575b424678c01e2369710fc55fa255887aad3d94510b52eaf61a13",
            "ReversibleThrusterAllocator", "134608a64f65376c65856ae9a204b05e17a400154c2b464726d387fcbd4e69d5",
            "FixedWingFlapSnapshot", "8b801bc398f4042b1c557a7ae91b3ac5665f6dc8968a71f3a745970c22da8afa",
            "FixedWingPassiveSnapshot", "a31d8335a0f886ff1aab77373e12b8f3e4a90e1cbdee824fd4cd8267d4a70e13");
    private static volatile boolean applied;
    private static volatile boolean budgetApplied;
    private CruiseTrimCompatibility() { }
    public static boolean supports(String version, Map<String, String> hashes, int callCount) {
        return "0.8.3".equals(version) && callCount == 1 && PINNED_CLASSES.equals(hashes);
    }
    public static boolean isApplied() { return applied; }
    public static void markApplied() { applied = true; }
    public static boolean supportsBudgetPropagation(String version, String computerHash, String budgetHash, int calls) {
        return "0.8.3".equals(version) && calls == 1
                && "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1".equals(computerHash)
                && "cc59ae2d943e6551a17905d9b9613405d6bab46a397b9ec90b6cfe261f672d7b".equals(budgetHash);
    }
    public static boolean isBudgetPropagationApplied() { return budgetApplied; }
    public static void markBudgetPropagationApplied() { budgetApplied = true; }
}
