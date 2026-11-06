package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class CruiseTrimCompatibilityTest {
    private static final Map<String, String> EXACT = Map.of(
            "FixedWingCruise", "7fa9625ec1965e730750bbf6eebcdf45ec998c3ff12559ff81d5694221c69d3d",
            "FixedWingLevelTrim", "5f4c4b31768607e6d3876b5a309d71b077fc07af25938e353d3ef2aff4fd87b0",
            "FixedWingPerformance", "7ea1771e78575b424678c01e2369710fc55fa255887aad3d94510b52eaf61a13",
            "ReversibleThrusterAllocator", "134608a64f65376c65856ae9a204b05e17a400154c2b464726d387fcbd4e69d5",
            "FixedWingFlapSnapshot", "8b801bc398f4042b1c557a7ae91b3ac5665f6dc8968a71f3a745970c22da8afa",
            "FixedWingPassiveSnapshot", "a31d8335a0f886ff1aab77373e12b8f3e4a90e1cbdee824fd4cd8267d4a70e13");

    @Test void acceptsOnlyTheInspectedNativeMathAndSingleCruiseCall() {
        assertTrue(CruiseTrimCompatibility.supports("0.8.3", EXACT, 1));
    }
    @Test void unknownVersionsAndMissingOrAmbiguousCallsRemainUnmodified() {
        for (String version : new String[]{null, "", "0.8.2", "0.8.4", "0.8.3-custom"})
            assertFalse(CruiseTrimCompatibility.supports(version, EXACT, 1));
        for (int calls : new int[]{-1, 0, 2, 3})
            assertFalse(CruiseTrimCompatibility.supports("0.8.3", EXACT, calls));
    }
    @Test void everyMathematicalClassMustMatchInsteadOfTrustingTheVersionLabel() {
        assertFalse(CruiseTrimCompatibility.supports("0.8.3", null, 1));
        assertFalse(CruiseTrimCompatibility.supports("0.8.3", Map.of(), 1));
        for (String name : EXACT.keySet()) {
            var changed = new HashMap<>(EXACT);
            changed.put(name, "unreviewed");
            assertFalse(CruiseTrimCompatibility.supports("0.8.3", changed, 1), name);
            changed.remove(name);
            assertFalse(CruiseTrimCompatibility.supports("0.8.3", changed, 1), name);
        }
    }
    @Test void budgetInheritanceRequiresTheExactComputerBudgetAndSingleDispatch() {
        String computer = "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1";
        String budget = "cc59ae2d943e6551a17905d9b9613405d6bab46a397b9ec90b6cfe261f672d7b";
        assertTrue(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.3", computer, budget, 1));
        assertFalse(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.4", computer, budget, 1));
        assertFalse(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.3", "changed", budget, 1));
        assertFalse(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.3", computer, "changed", 1));
        assertFalse(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.3", computer, budget, 0));
        assertFalse(CruiseTrimCompatibility.supportsBudgetPropagation("0.8.3", computer, budget, 2));
    }
}
