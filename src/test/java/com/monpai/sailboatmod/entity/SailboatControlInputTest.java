package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SailboatControlInputTest {
    @Test
    void leftKeyOnlyTurnsWithoutChangingGearIntent() {
        SailboatControlInput input = SailboatControlInput.fromKeys(true, false, false, true, false);

        assertFalse(input.wantsForward());
        assertFalse(input.wantsReverse());
        assertTrue(input.wantsTurn());
        assertEquals(1.0F, input.turnInput(), 1.0E-6F);
    }

    @Test
    void rightKeyOnlyTurnsWithoutChangingGearIntent() {
        SailboatControlInput input = SailboatControlInput.fromKeys(true, false, false, false, true);

        assertFalse(input.wantsForward());
        assertFalse(input.wantsReverse());
        assertTrue(input.wantsTurn());
        assertEquals(-1.0F, input.turnInput(), 1.0E-6F);
    }

    @Test
    void forwardAndBackGearIntentsAreExclusive() {
        SailboatControlInput forward = SailboatControlInput.fromKeys(true, true, false, false, false);
        SailboatControlInput back = SailboatControlInput.fromKeys(true, false, true, false, false);
        SailboatControlInput conflicting = SailboatControlInput.fromKeys(true, true, true, false, false);

        assertTrue(forward.wantsForward());
        assertFalse(forward.wantsReverse());
        assertFalse(back.wantsForward());
        assertTrue(back.wantsReverse());
        assertFalse(conflicting.wantsForward());
        assertFalse(conflicting.wantsReverse());
    }

    @Test
    void disabledControlsIgnoreHeldKeys() {
        SailboatControlInput input = SailboatControlInput.fromKeys(false, true, true, true, true);

        assertFalse(input.hasManualInput());
        assertFalse(input.wantsForward());
        assertFalse(input.wantsReverse());
        assertFalse(input.wantsTurn());
        assertEquals(0.0F, input.turnInput(), 1.0E-6F);
    }
}
