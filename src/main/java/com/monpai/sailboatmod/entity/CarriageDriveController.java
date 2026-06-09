package com.monpai.sailboatmod.entity;

import net.minecraft.util.Mth;

final class CarriageDriveController {
    private static final double BRAKE_BEFORE_REVERSE_SPEED = 0.035D;

    private CarriageDriveController() {
    }

    static CarriageDriveCommand createCommand(SailboatEntity.GroundDriveContext context, double currentForwardSpeed) {
        SailboatEntity.GroundDriveContext effectiveContext = context == null
                ? new SailboatEntity.GroundDriveContext(false, false, false, false, false, 0.0F, SailboatEntity.EngineGear.STOP)
                : context;
        float turnInput = Mth.clamp(effectiveContext.turnInput(), -1.0F, 1.0F);

        if (effectiveContext.autopilotControl() && !effectiveContext.hasManualInput()) {
            return fromAutopilotGear(effectiveContext.gear(), turnInput);
        }

        boolean forward = effectiveContext.wantsForward();
        boolean reverse = effectiveContext.wantsReverse();
        if (forward && !reverse) {
            return new CarriageDriveCommand(1.0D, VirtualHorseDriveState.MAX_FORWARD_SPEED, turnInput, false, true);
        }
        if (reverse && !forward) {
            if (currentForwardSpeed > BRAKE_BEFORE_REVERSE_SPEED) {
                return new CarriageDriveCommand(0.0D, 0.0D, turnInput, true, true);
            }
            return new CarriageDriveCommand(-1.0D, -VirtualHorseDriveState.MAX_REVERSE_SPEED, turnInput, false, true);
        }
        if (forward && reverse) {
            return new CarriageDriveCommand(0.0D, 0.0D, turnInput, true, true);
        }
        return new CarriageDriveCommand(0.0D, 0.0D, turnInput, false, effectiveContext.hasManualInput());
    }

    private static CarriageDriveCommand fromAutopilotGear(SailboatEntity.EngineGear gear, float turnInput) {
        SailboatEntity.EngineGear effectiveGear = gear == null ? SailboatEntity.EngineGear.STOP : gear;
        if (effectiveGear == SailboatEntity.EngineGear.STOP) {
            return new CarriageDriveCommand(0.0D, 0.0D, turnInput, true, false);
        }
        double targetSpeed = effectiveGear.targetSpeed(
                VirtualHorseDriveState.MAX_FORWARD_SPEED,
                VirtualHorseDriveState.MAX_REVERSE_SPEED,
                false
        );
        return new CarriageDriveCommand(Math.signum(targetSpeed), targetSpeed, turnInput, false, false);
    }
}
