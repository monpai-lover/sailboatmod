package com.monpai.sailboatmod.transport.air;
import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.Action;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightResumePolicyTest {
    @Test void readyProfileMustReenterPreflightBeforeStartingAReplacementLease() {
        assertNull(FlightResumePolicy.action("READY"));
        assertEquals(Action.RESUME,FlightResumePolicy.action("PAUSED"));
        assertEquals(Action.RESUME,FlightResumePolicy.action("PREEMPTED"));
    }
    @Test void neverStartsOverAnotherActiveOrUnvalidatedTask() {
        for(String phase:new String[]{"RUNNING","HOLDING","TRANSITIONING","UNAVAILABLE","COMPLETED","BLOCKED"})
            assertNull(FlightResumePolicy.action(phase),phase);
    }
    @Test void airportWaitingRetainsTheAcceptedDepartureButHardFailuresDoNot() {
        assertTrue(FlightResumePolicy.waitsForAirport("airport_unavailable"));
        assertTrue(FlightResumePolicy.waitsForAirport("airport_reserved"));
        assertTrue(FlightResumePolicy.waitsForAirport("waiting_airport_metadata"));
        assertTrue(FlightResumePolicy.waitsForAirport("waiting_airport_terrain"));
        assertFalse(FlightResumePolicy.waitsForAirport("airport_loading_too_large"));
        assertFalse(FlightResumePolicy.waitsForAirport("flight_control_conflict"));
        assertFalse(FlightResumePolicy.waitsForAirport("generated_profile_invalid"));
    }
}
