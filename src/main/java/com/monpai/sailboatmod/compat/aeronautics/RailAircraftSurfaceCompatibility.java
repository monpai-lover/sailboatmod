package com.monpai.sailboatmod.compat.aeronautics;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Original class resources are inspected before either optional target is loaded. */
public final class RailAircraftSurfaceCompatibility {
    public static final String BEARING = "com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity";
    public static final String CONTRAPTION = "com.simibubi.create.content.contraptions.ControlledContraptionEntity";
    public static final Map<String, String> PINS = Map.of(
            BEARING, "6c6e5bdabd3ecbd7003213e8b597469553c916c7dc0900abef4f21975c69bd00",
            CONTRAPTION, "71af0939585326ba57ce7064b6b399750416f0814ada128d28da24e107de77ee");
    private static final AtomicBoolean APPLIED = new AtomicBoolean();
    private RailAircraftSurfaceCompatibility() { }
    public static boolean applied() { return APPLIED.get(); }
    public static void markApplied(String target) { if (BEARING.equals(target)) APPLIED.set(true); }
    public static boolean supports(String version, Map<String, byte[]> classes) {
        if (!"6.0.10".equals(version) || classes == null) return false;
        try {
            for (var pin : PINS.entrySet()) {
                byte[] bytes = classes.get(pin.getKey());
                if (bytes == null || bytes.length == 0 || bytes.length > 1024 * 1024 || !pin.getValue().equals(
                        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))) return false;
            }
            return true;
        } catch (java.security.NoSuchAlgorithmException impossible) { return false; }
    }
}
