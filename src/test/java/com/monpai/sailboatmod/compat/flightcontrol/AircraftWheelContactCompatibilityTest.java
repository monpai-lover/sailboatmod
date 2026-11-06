package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AircraftWheelContactCompatibilityTest {
    @Test void onlyTheInspectedNativeForceSiteCanEnableCompatibility() {
        assertTrue(AircraftWheelContactCompatibility.supports("1.3.2",
                AircraftWheelContactCompatibility.WHEEL_SHA256,1));
        assertFalse(AircraftWheelContactCompatibility.supports("1.3.3",
                AircraftWheelContactCompatibility.WHEEL_SHA256,1));
        assertFalse(AircraftWheelContactCompatibility.supports("1.3.2","unverified",1));
        assertFalse(AircraftWheelContactCompatibility.supports("1.3.2",
                AircraftWheelContactCompatibility.WHEEL_SHA256,0));
        assertFalse(AircraftWheelContactCompatibility.supports("1.3.2",
                AircraftWheelContactCompatibility.WHEEL_SHA256,2));
    }
}
