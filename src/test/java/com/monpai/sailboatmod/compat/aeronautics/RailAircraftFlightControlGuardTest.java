package com.monpai.sailboatmod.compat.aeronautics;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class RailAircraftFlightControlGuardTest {
    final Object computer = new Object(), unrelated = new Object();
    RailAircraftFlightControlGuard.Readback quiet() { return new RailAircraftFlightControlGuard.Readback(false, false, 0, 0); }

    @Test void noComputerNeedsNeitherAnOptionalClassNorAMembershipQuery() {
        assertTrue(RailAircraftFlightControlGuard.inspect(List.of(unrelated), 4,
                actor -> { throw new AssertionError("No computer means no body query"); }, actor -> null).success());
    }
    @Test void quietSameBodyComputerIsAllowedButEveryLiveControlSignalIsAConflict() {
        assertTrue(RailAircraftFlightControlGuard.inspect(List.of(computer), 4, actor -> true, actor -> quiet()).success());
        for (var state : List.of(new RailAircraftFlightControlGuard.Readback(true, false, 0, 0),
                new RailAircraftFlightControlGuard.Readback(false, true, 0, 0),
                new RailAircraftFlightControlGuard.Readback(false, false, 1, 0),
                new RailAircraftFlightControlGuard.Readback(false, false, 0, 1))) {
            var result = RailAircraftFlightControlGuard.inspect(List.of(computer), 4, actor -> true, actor -> state);
            assertFalse(result.success());
            assertEquals("flight_control_conflict", result.reason());
        }
    }
    @Test void staleMembershipUnknownAbiAndActorOverflowNeverCountAsFreeControl() {
        assertFalse(RailAircraftFlightControlGuard.inspect(List.of(computer), 4, actor -> false, actor -> quiet()).success());
        var unknown = RailAircraftFlightControlGuard.inspect(List.of(computer), 4, actor -> true,
                actor -> { throw new ReflectiveOperationException("unknown ABI"); });
        assertEquals("flight_control_state_unavailable", unknown.reason());
        assertEquals("flight_control_actor_limit", RailAircraftFlightControlGuard.inspect(
                List.of(unrelated, unrelated, computer), 2, actor -> true, actor -> null).reason());
    }
    @Test void eachComputerOnTheBodyIsCheckedEvenWhenTheFirstIsQuiet() {
        Object second = new Object();
        var result = RailAircraftFlightControlGuard.inspect(List.of(computer, unrelated, second), 4, actor -> true,
                actor -> actor == unrelated ? null : actor == computer ? quiet()
                        : new RailAircraftFlightControlGuard.Readback(true, false, 0, 0));
        assertEquals("flight_control_conflict", result.reason());
    }
    @Test void malformedNativeCountsAreRejected() {
        assertEquals("flight_control_state_unavailable", RailAircraftFlightControlGuard.inspect(List.of(computer), 4,
                actor -> true, actor -> new RailAircraftFlightControlGuard.Readback(false, false, -1, 0)).reason());
    }
}
