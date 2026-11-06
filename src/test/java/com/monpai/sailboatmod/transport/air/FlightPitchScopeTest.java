package com.monpai.sailboatmod.transport.air;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class FlightPitchScopeTest {
    @Test void rejectedNestedAircraftCannotBorrowOuterEligibility() {
        var context = new FlightPitchScope<String>();
        try (var outer = context.enter("aircraft-a")) {
            assertEquals("aircraft-a", context.current());
            try (var rejected = context.enter(null)) { assertNull(context.current()); }
            assertEquals("aircraft-a", context.current());
        }
        assertNull(context.current());
    }
    @Test void nativeExceptionRestoresOuterScopeWithoutReplayingOperation() {
        var context = new FlightPitchScope<String>();
        int[] calls = {0};
        try (var outer = context.enter("outer")) {
            assertThrows(IllegalArgumentException.class, () -> {
                try (var inner = context.enter("inner")) {
                    calls[0]++;
                    throw new IllegalArgumentException("native failure");
                }
            });
            assertEquals("outer", context.current());
            assertEquals(1, calls[0]);
        }
        assertNull(context.current());
    }
    @Test void anotherThreadNeverInheritsAnAircraftScope() throws Exception {
        var context = new FlightPitchScope<String>();
        var other = new AtomicReference<String>("not-run");
        var failure = new AtomicReference<Throwable>();
        try (var outer = context.enter("owned")) {
            var thread = new Thread(() -> {
                try {
                    other.set(context.current());
                    try (var inner = context.enter("other")) { assertEquals("other", context.current()); }
                } catch (Throwable unexpected) { failure.set(unexpected); }
            });
            thread.start(); thread.join();
            assertNull(failure.get()); assertNull(other.get()); assertEquals("owned", context.current());
        }
        assertNull(context.current());
    }
    @Test void closingOutOfOrderIsRejectedWithoutRestoringStalePermission() {
        var context = new FlightPitchScope<String>();
        var outer = context.enter("outer"); var inner = context.enter("inner");
        assertThrows(IllegalStateException.class, outer::close);
        assertEquals("inner", context.current());
        inner.close(); assertEquals("outer", context.current());
        outer.close(); outer.close(); assertNull(context.current());
    }
}
