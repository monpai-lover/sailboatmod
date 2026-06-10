package com.monpai.sailboatmod.client;

import com.monpai.sailboatmod.entity.CarriageDriveInput;
import com.monpai.sailboatmod.entity.SailboatControlInput;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarriageClientInputHandlerTest {
    @Test
    void forwardLeftKeysBuildImmediateLocalDriveInput() {
        CarriageDriveInput input = ClientInputHandler.carriageControlInputForTest(
                true,
                true,
                false,
                true,
                false,
                0.0F,
                0.0F
        );

        assertEquals(CarriageDriveInput.AccelerationDirection.FORWARD, input.acceleration());
        assertEquals(CarriageDriveInput.TurnDirection.LEFT, input.turn());
        assertTrue(input.targetTurnAngle() > 0.0F);
        assertEquals(1.0F, input.power(), 1.0E-6F);
    }

    @Test
    void disabledControlsBuildIdleInputForLocalPrediction() {
        CarriageDriveInput input = ClientInputHandler.carriageControlInputForTest(
                false,
                true,
                false,
                true,
                false,
                12.0F,
                4.0F
        );

        assertEquals(CarriageDriveInput.AccelerationDirection.NONE, input.acceleration());
        assertEquals(CarriageDriveInput.TurnDirection.FORWARD, input.turn());
        assertTrue(input.targetTurnAngle() < 12.0F);
        assertEquals(0.0F, input.power(), 1.0E-6F);
    }

    @Test
    void disabledSailboatControlsBuildIdleInput() {
        SailboatControlInput input = ClientInputHandler.sailboatControlInputForTest(
                false,
                true,
                true,
                true,
                true
        );

        assertFalse(input.hasManualInput());
        assertFalse(input.wantsForward());
        assertFalse(input.wantsReverse());
        assertFalse(input.wantsTurn());
    }
}
