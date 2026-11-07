package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Map;

/** Optional measured-pitch feedback; both hooks must match the inspected native implementation. */
public final class PitchBootstrapCompatibility {
    public static final Map<String, String> PINNED_CLASSES = Map.of(
            "ace.flight.block.NavigationTerminalBlockEntity", "e906e63530dc02322269e96e9c8e23d9ad9de23aaf579cfaa20eb7cf60fc7c0a",
            "ace.flight.navigation.NavigationRouteFollower", "8f4c72db98905a84d9de2ec77a597d23e0b9cf5bd550d966699c740a74c55b94",
            "ace.flight.navigation.FixedWingGuidance", "cac8c38c7b3aa31d9eddf4f54a660bc1a0e4fd2693e18b4980b6f4f960260faa");
    private static volatile boolean scopeApplied;
    private static volatile boolean measurementApplied;
    private static volatile boolean guidanceApplied;
    private PitchBootstrapCompatibility() { }
    public static boolean supports(String version, Map<String, String> classHashes, int scopedUpdateCalls,
                                   int demandCalls, int compensationStores) {
        return "0.8.3".equals(version) && PINNED_CLASSES.equals(classHashes)
                && scopedUpdateCalls == 1 && demandCalls == 1 && compensationStores == 2;
    }
    public static boolean isApplied() { return scopeApplied && measurementApplied && guidanceApplied; }
    public static void markScopeApplied() { scopeApplied = true; }
    public static void markMeasurementApplied() { measurementApplied = true; }
    public static void markGuidanceApplied() { guidanceApplied = true; }
}
