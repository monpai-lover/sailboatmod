package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftMotionOptionalLoadingTest {
    @Test void mainApiLoadsAndRejectsMissingControllerWithoutAnyOptionalNativeClass() {
        if (Boolean.getBoolean("sailboat.test.optionalModsAbsent")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName("dev.ryanhcode.sable.Sable"));
            assertThrows(ClassNotFoundException.class, () -> Class.forName("ace.flight.block.FlightControlComputerBlockEntity"));
        }
        assertFalse(RailAircraftMotionBridge.available(null));
        assertTrue(RailAircraftMotionBridge.angularSpeed(null).isEmpty());
        assertFalse(RailAircraftMotionBridge.foreignFlightControlStatus(null).success());
        assertFalse(RailAircraftMotionBridge.owns(null, null));
        assertFalse(RailAircraftMotionBridge.ownedBy(null,null,null));
        assertFalse(RailAircraftMotionBridge.acquire(null, null, null, null).accepted());
        assertFalse(RailAircraftMotionBridge.hold(null, null).success());
        assertFalse(RailAircraftMotionBridge.status(null, null).success());
        assertFalse(RailAircraftMotionBridge.move(null, null, null, null, 0).success());
        assertFalse(RailAircraftMotionBridge.release(null, null).success());
        assertFalse(RailAircraftMotionBridge.matchesTarget(null, null, null, null, 0, .05, .02));
        assertTrue(RailAircraftMotionBridge.sweptBounds(null,null,null,null,null,0).isEmpty());
    }
}
