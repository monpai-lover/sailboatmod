package com.monpai.sailboatmod.compat.flightcontrol;

/** Exact-binary gate candidate; only this compatibility Mixin's postApply marks it applied. */
public final class NativeGroundNeutralCompatibility {
    public static final String NATIVE_JAR_SHA256 = "86691e6965bb9c3e54ef71001a4ebea798e1ccc9b30ccdb731538933f1379208";
    public static final String COMPUTER_SHA256 = "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1";
    private static volatile boolean applied;
    private NativeGroundNeutralCompatibility() { }
    public static boolean supports(String version, String jarHash, String computerHash, int hookCalls) {
        return "0.8.3".equals(version) && NATIVE_JAR_SHA256.equals(jarHash)
                && COMPUTER_SHA256.equals(computerHash) && hookCalls == 1;
    }
    public static boolean isApplied() { return applied; }
    public static void markApplied() { applied = true; }
}
