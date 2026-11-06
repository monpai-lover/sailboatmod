package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NativeTakeoffAssistBrakesTest {
    // FC 0.8.3 allowBrakes becomes true above 1.05 VR. It is an aerodynamic
    // planning permission, whereas all four actual wheel brake inputs remain zero.
    private boolean released(int... actualWheelSignals) {
        return NativeTakeoffAssistEvidence.wheelBrakesReleased(actualWheelSignals);
    }
    @Test void unlockedWheelBrakesStayReleasedBeyondTheAerodynamicBrakePermissionThreshold() {
        assertTrue(released(0, 0, 0, 0));
    }
    @Test void anyActualWheelBrakeSignalPreventsAssistance() {
        for (int wheel = 0; wheel < 4; wheel++) {
            int[] signals = new int[4]; signals[wheel] = 1;
            assertFalse(released(signals));
            signals[wheel] = 15;
            assertFalse(released(signals));
        }
    }
    @Test void missingOrInvalidBrakeObservationsCannotAuthorizeAssistance() {
        assertFalse(released());
        assertFalse(released(0, 0));
        assertFalse(released(-1, 0, 0, 0));
        assertFalse(released(16, 0, 0, 0));
        assertFalse(released(new int[33]));
    }
}
