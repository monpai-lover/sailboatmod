package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PitchBootstrapFlightPhaseTest {
    @Test void ordinaryDirectCruiseIsEligible() {
        assertTrue(new PitchBootstrapFlightPhase(true,false,false,false,false,false,false).allowsMeasuredPitch());
    }
    @Test void finalDirectWaypointHoldingCannotBorrowCruiseEligibility() {
        assertFalse(new PitchBootstrapFlightPhase(true,false,false,false,false,true,false).allowsMeasuredPitch());
        assertFalse(new PitchBootstrapFlightPhase(true,false,false,false,false,false,true).allowsMeasuredPitch());
    }
    @Test void transitionDuringTheSameUpdateRevokesCruiseEligibility() {
        assertFalse(new PitchBootstrapFlightPhase(true,false,false,true,false,false,false).allowsMeasuredPitch());
        assertFalse(new PitchBootstrapFlightPhase(true,false,true,false,false,false,false).allowsMeasuredPitch());
        assertFalse(new PitchBootstrapFlightPhase(true,true,false,false,false,false,false).allowsMeasuredPitch());
        assertFalse(new PitchBootstrapFlightPhase(true,false,false,false,true,false,false).allowsMeasuredPitch());
        assertFalse(new PitchBootstrapFlightPhase(false,false,false,false,false,false,false).allowsMeasuredPitch());
    }
}
