package com.monpai.sailboatmod.compat.flightcontrol;

/** Optional Offroad hook, pinned to the inspected native force-application method. */
public final class AircraftWheelContactCompatibility {
    public static final String WHEEL_SHA256 = "fb2d03d3c3ccf2219d53c3e6bf8327017913c8fd00574c1a9d14b64b341c6fd1";
    private static volatile boolean applied;
    private AircraftWheelContactCompatibility() { }
    public static boolean supports(String version, String classHash, int applicationCalls) {
        return "1.3.2".equals(version) && WHEEL_SHA256.equals(classHash) && applicationCalls == 1;
    }
    public static boolean isApplied() { return applied; }
    public static void markApplied() { applied = true; }
}
