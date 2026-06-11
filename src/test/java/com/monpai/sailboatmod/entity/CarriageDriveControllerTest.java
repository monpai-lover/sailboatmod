package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageDriveControllerTest {
    @Test
    void forwardInputCreatesHoldToDriveCommandWithoutNeedingAGearShift() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(false, true, true, false, false, 0.0F, SailboatEntity.EngineGear.STOP),
                0.0D
        );

        assertEquals(1.0D, command.driveIntent(), 1.0E-6D);
        assertEquals(VirtualHorseDriveState.MAX_FORWARD_SPEED, command.targetSpeed(), 1.0E-6D);
        assertFalse(command.braking());
        assertTrue(command.manualControl());
    }

    @Test
    void releasingThrottleCoastsInsteadOfApplyingTheBrake() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(false, false, false, false, false, 0.0F, SailboatEntity.EngineGear.STOP),
                0.22D
        );

        assertEquals(0.0D, command.driveIntent(), 1.0E-6D);
        assertEquals(0.0D, command.targetSpeed(), 1.0E-6D);
        assertFalse(command.braking());
    }

    @Test
    void reverseInputBrakesBeforeEnteringReverseWhenStillMovingForward() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(false, true, false, true, false, 0.0F, SailboatEntity.EngineGear.STOP),
                0.12D
        );

        assertEquals(0.0D, command.driveIntent(), 1.0E-6D);
        assertEquals(0.0D, command.targetSpeed(), 1.0E-6D);
        assertTrue(command.braking());
    }

    @Test
    void reverseInputAtLowSpeedCommandsReverse() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(false, true, false, true, false, 0.0F, SailboatEntity.EngineGear.STOP),
                0.01D
        );

        assertEquals(-1.0D, command.driveIntent(), 1.0E-6D);
        assertEquals(-VirtualHorseDriveState.MAX_REVERSE_SPEED, command.targetSpeed(), 1.0E-6D);
        assertFalse(command.braking());
    }

    @Test
    void autopilotGearMapsToCarriageTargetSpeed() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(true, false, false, false, false, 0.35F, SailboatEntity.EngineGear.TWO_THIRDS_AHEAD),
                0.0D
        );

        assertTrue(command.driveIntent() > 0.0D);
        assertTrue(command.targetSpeed() > 0.0D);
        assertEquals(0.35F, command.turnInput(), 1.0E-6F);
        assertFalse(command.manualControl());
    }

    @Test
    void autopilotRouteCommandWinsOverPassengerControlInput() {
        CarriageDriveCommand command = CarriageDriveController.createCommand(
                new SailboatEntity.GroundDriveContext(true, true, false, true, true, -1.0F, SailboatEntity.EngineGear.FULL_AHEAD),
                0.0D
        );

        assertTrue(command.driveIntent() > 0.0D);
        assertTrue(command.targetSpeed() > 0.0D);
        assertEquals(-1.0F, command.turnInput(), 1.0E-6F);
        assertFalse(command.braking());
        assertFalse(command.manualControl());
    }
}
