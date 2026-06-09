package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;

record CarriageDriveCommand(double driveIntent,
                            double targetSpeed,
                            float turnInput,
                            boolean braking,
                            boolean manualControl) {
    CarriageDriveCommand {
        driveIntent = Mth.clamp(driveIntent, -1.0D, 1.0D);
        targetSpeed = Mth.clamp(targetSpeed, -VirtualHorseDriveState.MAX_REVERSE_SPEED, VirtualHorseDriveState.MAX_FORWARD_SPEED);
        turnInput = Mth.clamp(turnInput, -1.0F, 1.0F);
    }
}
