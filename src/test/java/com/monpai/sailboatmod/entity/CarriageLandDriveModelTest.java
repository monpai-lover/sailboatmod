package com.monpai.sailboatmod.entity;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageLandDriveModelTest {
    @Test
    void forwardAccelerationMovesWithoutTurnInput() {
        CarriageLandDriveModel.State state = CarriageLandDriveModel.State.idle(0.0F);
        CarriageDriveInput input = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.FORWARD,
                CarriageDriveInput.TurnDirection.FORWARD,
                0.0F,
                1.0F
        );

        for (int i = 0; i < 12; i++) {
            state = CarriageLandDriveModel.step(state, input, CarriageLandDriveModel.Environment.road());
        }

        assertTrue(state.currentSpeed() > 0.0F);
        assertTrue(state.deltaMovement().z > 0.0D);
        assertEquals(0.0F, state.yaw(), 1.0E-4F);
    }

    @Test
    void holdingLeftChangesYawWhileMoving() {
        CarriageLandDriveModel.State state = CarriageLandDriveModel.State.idle(0.0F);
        CarriageDriveInput forward = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.FORWARD,
                CarriageDriveInput.TurnDirection.FORWARD,
                0.0F,
                1.0F
        );
        for (int i = 0; i < 10; i++) {
            state = CarriageLandDriveModel.step(state, forward, CarriageLandDriveModel.Environment.road());
        }

        CarriageDriveInput left = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.FORWARD,
                CarriageDriveInput.TurnDirection.LEFT,
                18.0F,
                1.0F
        );
        for (int i = 0; i < 8; i++) {
            state = CarriageLandDriveModel.step(state, left, CarriageLandDriveModel.Environment.road());
        }

        assertTrue(Math.abs(state.yaw()) > 1.0F);
        assertTrue(Math.abs(state.wheelAngle()) > 1.0F);
    }

    @Test
    void leftInputTurnsTowardNegativeMinecraftYawWhenFacingSouth() {
        CarriageLandDriveModel.State state = CarriageLandDriveModel.State.idle(0.0F);
        CarriageDriveInput left = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.FORWARD,
                CarriageDriveInput.TurnDirection.LEFT,
                CarriageLandDriveModel.MAX_TURN_ANGLE,
                1.0F
        );

        for (int i = 0; i < 8; i++) {
            state = CarriageLandDriveModel.step(state, left, CarriageLandDriveModel.Environment.road());
        }

        assertTrue(state.yaw() < -1.0F);
        assertTrue(state.deltaMovement().x > 0.0D);
    }

    @Test
    void reverseInputBrakesBeforeBackingUp() {
        CarriageLandDriveModel.State state = new CarriageLandDriveModel.State(
                5.0F,
                0.0F,
                0.0F,
                0.0F,
                new Vec3(0.0D, 0.0D, 0.25D)
        );
        CarriageDriveInput reverse = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.REVERSE,
                CarriageDriveInput.TurnDirection.FORWARD,
                0.0F,
                1.0F
        );

        CarriageLandDriveModel.State next = CarriageLandDriveModel.step(state, reverse, CarriageLandDriveModel.Environment.road());

        assertTrue(next.currentSpeed() >= 0.0F);
        assertTrue(next.currentSpeed() < state.currentSpeed());
    }
}
