package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotPassengerInputPolicyTest {
    @Test
    void activeAutopilotKeepsRouteControlEvenWhenPassengerPressesControls() {
        assertTrue(AutopilotPassengerInputPolicy.shouldUseAutopilotCommand(true, true));
        assertTrue(AutopilotPassengerInputPolicy.shouldUseAutopilotCommand(true, false));
        assertFalse(AutopilotPassengerInputPolicy.shouldUseAutopilotCommand(false, true));
    }

    @Test
    void passengerControlsDoNotCancelActiveAutopilot() {
        CarriageDriveInput manualInput = new CarriageDriveInput(
                CarriageDriveInput.AccelerationDirection.FORWARD,
                CarriageDriveInput.TurnDirection.LEFT,
                20.0F,
                1.0F
        );

        assertFalse(AutopilotPassengerInputPolicy.shouldCancelAutopilotForPassengerInput(true, manualInput));
        assertFalse(AutopilotPassengerInputPolicy.shouldCancelAutopilotForPassengerInput(true, CarriageDriveInput.idle()));
        assertFalse(AutopilotPassengerInputPolicy.shouldCancelAutopilotForPassengerInput(false, manualInput));
    }
}
