package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class CarriageManualInputStateTest {
    @Test
    void cachedVehicleInputSuppliesContinuousForwardAndTurnState() {
        CarriageManualInputState state = new CarriageManualInputState();
        state.update(true, false, true, false, 10);

        SailboatEntity.GroundDriveContext context = state.applyTo(
                new SailboatEntity.GroundDriveContext(false, false, false, false, false, 0.0F, SailboatEntity.EngineGear.STOP),
                11
        );

        assertTrue(context.hasManualInput());
        assertTrue(context.wantsForward());
        assertFalse(context.wantsReverse());
        assertTrue(context.wantsTurn());
        assertEquals(1.0F, context.turnInput(), 1.0E-6F);
    }

    @Test
    void staleVehicleInputDoesNotKeepDrivingAfterPacketsStop() {
        CarriageManualInputState state = new CarriageManualInputState();
        state.update(false, true, true, false, 10);

        SailboatEntity.GroundDriveContext context = state.applyTo(
                new SailboatEntity.GroundDriveContext(false, false, false, false, false, 0.0F, SailboatEntity.EngineGear.STOP),
                18
        );

        assertFalse(context.hasManualInput());
        assertFalse(context.wantsForward());
        assertFalse(context.wantsTurn());
        assertEquals(0.0F, context.turnInput(), 1.0E-6F);
    }
}
