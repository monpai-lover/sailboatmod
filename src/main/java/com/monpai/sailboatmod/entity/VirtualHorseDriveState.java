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
        double targetSpeed = 0.0D;
        if (!braking && effectiveGear != SailboatEntity.EngineGear.STOP && Math.abs(clampedIntent) >= 1.0E-4D) {
            targetSpeed = effectiveGear.targetSpeed(MAX_FORWARD_SPEED, MAX_REVERSE_SPEED, false);
            if (clampedIntent < 0.0D && targetSpeed > 0.0D) {
                targetSpeed = -Math.min(MAX_REVERSE_SPEED, targetSpeed);
            }
        }
        return updateTowardCommand(clampedIntent, headingIntent, targetSpeed, braking || effectiveGear == SailboatEntity.EngineGear.STOP);
    }

    public VirtualHorseDriveState updateTowardCommand(double driveIntent,
                                                      float headingIntent,
                                                      double targetSpeed,
                                                      boolean braking) {
        double clampedIntent = Mth.clamp(driveIntent, -1.0D, 1.0D);
        double cappedTargetSpeed = Mth.clamp(targetSpeed, -MAX_REVERSE_SPEED, MAX_FORWARD_SPEED);
        boolean activeDrive = !braking && Math.abs(clampedIntent) >= 1.0E-4D && Math.abs(cappedTargetSpeed) >= 1.0E-4D;
        boolean reversing = cappedTargetSpeed < 0.0D || clampedIntent < 0.0D;

        double nextTargetSpeed = activeDrive ? cappedTargetSpeed : 0.0D;
        double nextTargetTraction = 0.0D;
        if (activeDrive) {
            double speedCap = reversing ? MAX_REVERSE_SPEED : MAX_FORWARD_SPEED;
            nextTargetTraction = Math.min(1.0D, Math.abs(nextTargetSpeed) / speedCap) * Math.abs(clampedIntent);
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
