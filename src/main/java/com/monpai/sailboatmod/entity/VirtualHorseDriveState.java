package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;

public record VirtualHorseDriveState(
        float currentHeading,
        float targetHeading,
        double currentTraction,
        double targetTraction,
        double targetSpeed,
        boolean braking,
        boolean reversing
) {
    public static final double MAX_FORWARD_SPEED = 0.55D;
    public static final double MAX_REVERSE_SPEED = 0.18D;
    private static final double TRACTION_RAMP = 0.18D;
    private static final float HEADING_RAMP = 0.20F;

    public static VirtualHorseDriveState idle() {
        return new VirtualHorseDriveState(0.0F, 0.0F, 0.0D, 0.0D, 0.0D, false, false);
    }

    public VirtualHorseDriveState updateTowardIntent(double driveIntent,
                                                     float headingIntent,
                                                     SailboatEntity.EngineGear gear,
                                                     boolean braking) {
        SailboatEntity.EngineGear effectiveGear = gear == null ? SailboatEntity.EngineGear.STOP : gear;
        double clampedIntent = Mth.clamp(driveIntent, -1.0D, 1.0D);
        boolean reversing = effectiveGear.id < 0 || clampedIntent < 0.0D;

        double nextTargetSpeed;
        double nextTargetTraction;
        if (braking || effectiveGear == SailboatEntity.EngineGear.STOP || Math.abs(clampedIntent) < 1.0E-4D) {
            nextTargetSpeed = 0.0D;
            nextTargetTraction = 0.0D;
        } else {
            double commandedSpeed = effectiveGear.targetSpeed(MAX_FORWARD_SPEED, MAX_REVERSE_SPEED, false);
            if (reversing) {
                nextTargetSpeed = -Math.min(MAX_REVERSE_SPEED, Math.abs(commandedSpeed));
                nextTargetTraction = Math.min(1.0D, Math.abs(nextTargetSpeed) / MAX_REVERSE_SPEED) * Math.abs(clampedIntent);
            } else {
                nextTargetSpeed = Math.max(0.0D, commandedSpeed);
                nextTargetTraction = Math.min(1.0D, nextTargetSpeed / MAX_FORWARD_SPEED) * Math.abs(clampedIntent);
            }
        }

        double nextTraction = Mth.lerp(TRACTION_RAMP, currentTraction, nextTargetTraction);
        return new VirtualHorseDriveState(
                Mth.rotLerp(HEADING_RAMP, currentHeading, headingIntent),
                headingIntent,
                nextTraction,
                nextTargetTraction,
                nextTargetSpeed,
                braking,
                reversing
        );
    }
}
