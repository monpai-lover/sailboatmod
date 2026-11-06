package com.monpai.sailboatmod.transport.air;

/** Registrar boundaries; flight completion never substitutes for arrival at a configured stand. */
public final class FixedWingGroundPolicy {
    public enum Mode { LEGACY, STAND, INCOMPLETE }
    private FixedWingGroundPolicy() { }

    public static Mode mode(boolean fixedWing, boolean sourceLayout, boolean destinationLayout) {
        if (!fixedWing || (!sourceLayout && !destinationLayout)) return Mode.LEGACY;
        return sourceLayout && destinationLayout ? Mode.STAND : Mode.INCOMPLETE;
    }

    public static boolean standDeliveryReady(String phase, boolean recovered, boolean assignedStand,
                                              boolean nativeGround, boolean bindingsMatch,
                                              double speed, double angularSpeed, int stableTicks) {
        return "COMPLETE".equals(phase) && !recovered && assignedStand && nativeGround && bindingsMatch
                && Double.isFinite(speed) && speed >= 0 && speed <= .25
                && Double.isFinite(angularSpeed) && angularSpeed >= 0 && angularSpeed <= Math.toRadians(2)
                && stableTicks >= 40;
    }
}
