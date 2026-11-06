package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class UnloadedWheelContactPolicyTest {
    @Test void fartherStaticRaysCannotHideANearerVehicleContact() {
        var floor = new UnloadedWheelContactPolicy.RayHit(true,true,true);
        var vehicle = new UnloadedWheelContactPolicy.RayHit(true,false,true);
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(vehicle,floor,floor)));
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(floor,vehicle,floor)));
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(floor,floor,vehicle)));
    }
    @Test void wallsAndUnknownOrAbsentHitsRetainNativeBehavior() {
        var floor = new UnloadedWheelContactPolicy.RayHit(true,true,true);
        var miss = new UnloadedWheelContactPolicy.RayHit(false,false,false);
        assertTrue(UnloadedWheelContactPolicy.staticFloorHits(List.of(miss,floor,miss)));
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(miss,miss,miss)));
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(floor,
                new UnloadedWheelContactPolicy.RayHit(true,true,false),floor)));
        assertFalse(UnloadedWheelContactPolicy.staticFloorHits(List.of(floor)));
    }
    @Test void actualUncompressedNativeWheelMustNotPullOrDragAircraft() {
        // Original aircraft, actual 40 Hz wheel observation, 2026-11-04.
        assertTrue(UnloadedWheelContactPolicy.ignore(1.4427869205379125, .75,
                -.8267429395746597, .998, .025));
    }
    @Test void loadedSuspensionRetainsItsNativeForces() {
        assertFalse(UnloadedWheelContactPolicy.ignore(1.24, .75, 8.2, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.24, .75, -.1, 1, .025));
    }
    @Test void positiveDampingSupportAtFullExtensionIsPreserved() {
        assertFalse(UnloadedWheelContactPolicy.ignore(1.44, .75, .01, 1, .025));
    }
    @Test void airborneSentinelAndNativeEarlyReturnAreNeverReinterpreted() {
        assertFalse(UnloadedWheelContactPolicy.ignore(.65, .75, -10, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.66, .75, -10, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(.65, .1, -10, 1, .025));
    }
    @Test void zeroLoadCannotSupplyHorizontalTraction() {
        assertTrue(UnloadedWheelContactPolicy.ignore(1.4, .75, 0, 1, .025));
    }
    @Test void usesThePlayersActualTireRadius() {
        assertTrue(UnloadedWheelContactPolicy.ignore(1.7, 1.1, -1, .8, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, 1.1, -1, .8, .025));
        assertTrue(UnloadedWheelContactPolicy.ignore(.99, .4, -1, .8, .025));
    }
    @Test void invalidOrInvertedEvidenceKeepsNativeBehavior() {
        assertFalse(UnloadedWheelContactPolicy.ignore(Double.NaN, .75, -1, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, 0, -1, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, .75, Double.NaN, 1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, .75, -1, .49, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, .75, -1, 1.1, .025));
        assertFalse(UnloadedWheelContactPolicy.ignore(1.4, .75, -1, 1, 0));
    }
}
