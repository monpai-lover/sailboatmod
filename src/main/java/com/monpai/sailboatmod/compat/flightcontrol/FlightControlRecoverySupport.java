package com.monpai.sailboatmod.compat.flightcontrol;

/** Plain shared flag: product code must never load a class inside the defined mixin package. */
public final class FlightControlRecoverySupport {
    private static volatile boolean applied;
    private FlightControlRecoverySupport() { }
    public static boolean isApplied() { return applied; }
    public static void markApplied() { applied = true; }
    public static boolean supports(String version, String terminalHash, String computerHash, int resumes, int starts) {
        return "0.8.3".equals(version)
                && "e906e63530dc02322269e96e9c8e23d9ad9de23aaf579cfaa20eb7cf60fc7c0a".equals(terminalHash)
                && "44487adde64fd7fa88b091fad99be96cfc0e26ec6d179b24334e3d186d9f5cd1".equals(computerHash)
                && resumes == 1 && starts == 2;
    }
}
