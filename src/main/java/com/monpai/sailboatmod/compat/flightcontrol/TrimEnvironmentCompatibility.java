package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Map;

/** Exact optional-native ABI gate. All three independently applied hooks are required. */
public final class TrimEnvironmentCompatibility {
    public static final String COMPUTER = "ace.flight.block.FlightControlComputerBlockEntity";
    public static final String NATIVE_JAR_SHA256 = "86691e6965bb9c3e54ef71001a4ebea798e1ccc9b30ccdb731538933f1379208";
    public static final String ALLOCATION = "cfc$physicsTickAllocation(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";
    public static final String SCOPE_METHOD = "cfc$physicsTickWithDiagnostics(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ldev/ryanhcode/sable/api/physics/handle/RigidBodyHandle;D)V";
    public static final String SCOPE_TARGET = "Lace/flight/block/FlightControlComputerBlockEntity;" + ALLOCATION;
    public static final String BUDGET_TARGET = "Lace/flight/block/VelocityCouplerBlockEntity;getAcceptedVelocityResponse()Lace/flight/physics/FixedWingVelocityResponse$Budget;";
    public static final String SAMPLE_TARGET = "Lace/flight/navigation/FixedWingAxialBudget$Sample;<init>(Lace/flight/navigation/NavigationVehicleStateSnapshot;JJLace/flight/navigation/FixedWingAxialBudget$Limits;J)V";
    public static final Map<String, String> PINNED_CLASSES = Map.of(
            COMPUTER, "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1",
            "ace.flight.navigation.FixedWingPitchTrim", "391554218ffc50ebbf5b7762cde71a88ccf6230c9d42792276c11501f08a37de",
            "ace.flight.navigation.FixedWingPitchTrim$Environment", "5466343992a7393acd63a26d5e2a825c84f0d55fa25d266808019f7ec81087d1",
            "ace.flight.navigation.FixedWingAxialBudget$Sample", "8b1ac4da043e253948a8680071095a2ed1b8398d101440d8d3f4c1c49423d601",
            "ace.flight.navigation.FixedWingAxialBudget$Limits", "2e1329ec7ec8bb2b689192ec134ae1cc27d5c12a3585a97f28ecac7b0d95c69a",
            "ace.flight.physics.NativeBodyFlapModel", "1cd3bdfebc31e56b8066453c6b31eeb1a9b0cd9ce0d409a18f1ae1d22712ac11",
            "ace.flight.physics.FixedWingVelocityResponse$Budget", "fc1a768c4c309e3140390ab7f939d8027b7b5f5859b0a985dc0da1d946716e42",
            "ace.flight.physics.FixedWingVelocityResponse$Axis", "6718813558efddf4a01cd8ce94f296f2001dbb0ad2131873c203917da77e5fbc",
            "ace.flight.block.FlightControlComputerBlockEntity$AxialResponseContext", "066e41f9c6d44ef4e85bd555309be4ec2cf32290da5f2702eed96d5e7b578d62");
    private static volatile boolean scopeApplied, budgetApplied, sampleApplied;
    private TrimEnvironmentCompatibility() { }
    public static boolean supports(String version, String jarHash, Map<String, String> classHashes,
                                   int scopeCalls, int budgetCalls, int sampleCalls) {
        return "0.8.3".equals(version) && NATIVE_JAR_SHA256.equals(jarHash)
                && PINNED_CLASSES.equals(classHashes) && scopeCalls == 1 && budgetCalls == 1 && sampleCalls == 1;
    }
    public static boolean isApplied() { return scopeApplied && budgetApplied && sampleApplied; }
    public static void markScopeApplied() { scopeApplied = true; }
    public static void markBudgetApplied() { budgetApplied = true; }
    public static void markSampleApplied() { sampleApplied = true; }
}
