package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SailboatManualInputStateTest {
    @Test
    void freshInputIsReturnedOnlyForSameCaptain() {
        SailboatManualInputState state = new SailboatManualInputState();
        UUID captain = UUID.randomUUID();

        state.update(captain, SailboatControlInput.fromKeys(true, false, false, true, false), 10);

        assertTrue(state.currentInput(captain, 11).wantsTurn());
        assertFalse(state.currentInput(UUID.randomUUID(), 11).hasManualInput());
    }

    @Test
    void staleInputDoesNotKeepDrivingAfterPacketsStop() {
        SailboatManualInputState state = new SailboatManualInputState();
        UUID captain = UUID.randomUUID();

        state.update(captain, SailboatControlInput.fromKeys(true, true, false, false, false), 10);

        assertFalse(state.currentInput(captain, 18).hasManualInput());
    }
}
