package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FlightRecoveryPolicyTest {
    @Test void persistedFlightIntentsAreDistinctFromLostRuntimeLeases() {
        assertTrue(FlightRecoveryPolicy.needsReloadGuard(true, false, false));
        assertFalse(FlightRecoveryPolicy.needsReloadGuard(true, true, false));
        assertFalse(FlightRecoveryPolicy.needsReloadGuard(true, false, true));
        assertFalse(FlightRecoveryPolicy.needsReloadGuard(false, false, false));
    }
    @Test void initializationWaitsWithoutTakingControlOfAnUnprovenTerminal() {
        assertEquals(FlightRecoveryPolicy.Guard.WAIT, FlightRecoveryPolicy.guard(false, true, "ours", "ours"));
        assertEquals(FlightRecoveryPolicy.Guard.WAIT, FlightRecoveryPolicy.guard(true, false, "ours", ""));
    }
    @Test void onlyTheInstalledOriginalCardCanBeDisabledDuringSlotInitialization() {
        assertEquals(FlightRecoveryPolicy.Guard.CANCEL_REPLAY, FlightRecoveryPolicy.guard(true, true, "ours", "ours"));
        assertEquals(FlightRecoveryPolicy.Guard.CONFLICT, FlightRecoveryPolicy.guard(true, true, "ours", "someone-else"));
        assertEquals(FlightRecoveryPolicy.Guard.CONFLICT, FlightRecoveryPolicy.guard(true, true, "", "ours"));
    }
    @Test void aReloadedMidairAircraftCannotAutomaticallyReplayItsOldRoute() {
        assertEquals(FlightRecoveryPolicy.Resume.REQUIRE_LANDING, FlightRecoveryPolicy.resume(false, false));
        assertEquals(FlightRecoveryPolicy.Resume.ARRIVE, FlightRecoveryPolicy.resume(false, true));
        assertEquals(FlightRecoveryPolicy.Resume.PREFLIGHT, FlightRecoveryPolicy.resume(true, false));
    }
}
