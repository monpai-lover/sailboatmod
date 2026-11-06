package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class NativeFixedWingTrimEnvironmentTest {
    private record Limits(boolean available, double acceleration, double braking, boolean environment) { }
    private static final Limits ORIGINAL = new Limits(false, -0.0, 0.0, false);
    private static final class Fixture implements NativeFixedWingTrimEnvironment.Access {
        final Object computer = TrimEnvironmentFrameTest.COMPUTER, body = TrimEnvironmentFrameTest.BODY, handle = TrimEnvironmentFrameTest.HANDLE;
        TrimEnvironmentFrame.State state = TrimEnvironmentFrameTest.state(10);
        int reads, writes;
        boolean unavailable;
        List<Object> recorded;
        NativeFixedWingTrimEnvironment.Scope enter() { return NativeFixedWingTrimEnvironment.enter(computer, body, handle, .025, this, true); }
        void advance() { state = TrimEnvironmentFrameTest.state(10.025); }
        Object supply(Object limits) { return NativeFixedWingTrimEnvironment.supplement(computer, body, handle, .025, state.snapshot(), 4, 5, limits, 6); }
        public TrimEnvironmentFrame.State read(Object c, Object b, Object h) throws ReflectiveOperationException {
            reads++; if (unavailable) throw new IllegalAccessException("unavailable"); return state;
        }
        public boolean known(Object limits) { return ((Limits)limits).environment(); }
        public Object supplement(Object limits, Object model, List<Object> budgets, TrimEnvironmentFrame.State current) {
            writes++; recorded = budgets; var old = (Limits)limits;
            return new Limits(old.available(), old.acceleration(), old.braking(), true);
        }
    }
    @Test void propagatesOnlyEnvironmentAndConsumesEvidenceOnce() {
        var f = new Fixture();
        try (var ignored = f.enter()) {
            Object first = new Object(), second = new Object();
            NativeFixedWingTrimEnvironment.captureBudget(f.computer, first);
            NativeFixedWingTrimEnvironment.captureBudget(f.computer, second);
            f.advance(); var result = (Limits)f.supply(ORIGINAL);
            assertFalse(result.available()); assertTrue(result.environment());
            assertEquals(Double.doubleToRawLongBits(-0.0), Double.doubleToRawLongBits(result.acceleration()));
            assertEquals(List.of(first, second), f.recorded);
            assertThrows(UnsupportedOperationException.class, () -> f.recorded.clear());
            assertSame(ORIGINAL, f.supply(ORIGINAL)); assertEquals(1, f.writes);
        }
        assertSame(ORIGINAL, f.supply(ORIGINAL));
    }
    @Test void nestedInvocationsCannotBorrowAnOuterModelOrBudget() {
        var f = new Fixture();
        try (var outer = f.enter()) {
            f.advance();
            try (var nested = f.enter()) {
                NativeFixedWingTrimEnvironment.captureBudget(f.computer, new Object());
                assertSame(ORIGINAL, f.supply(ORIGINAL));
            }
            assertTrue(((Limits)f.supply(ORIGINAL)).environment());
            assertEquals(List.of(), f.recorded);
        }
    }
    @Test void constructorHookUsesTheExactEnclosingOperationInputs() {
        var f = new Fixture();
        try (var ignored = f.enter()) {
            f.advance();
            var result = (Limits)NativeFixedWingTrimEnvironment.supplement(f.computer, f.state.snapshot(), 4, 5, ORIGINAL, 6);
            assertFalse(result.available()); assertTrue(result.environment());
            assertSame(ORIGINAL, NativeFixedWingTrimEnvironment.supplement(f.computer, f.state.snapshot(), 4, 5, ORIGINAL, 6));
        }
    }
    @Test void exceptionCleanupAndThreadIsolationLeaveNoUsableCapture() throws Exception {
        var f = new Fixture();
        assertThrows(IllegalStateException.class, () -> {
            try (var ignored = f.enter()) { f.advance(); throw new IllegalStateException("original allocation failed"); }
        });
        assertSame(ORIGINAL, f.supply(ORIGINAL));
        var next = new Fixture();
        try (var ignored = next.enter(); var executor = Executors.newSingleThreadExecutor()) {
            next.advance();
            assertSame(ORIGINAL, executor.submit(() -> next.supply(ORIGINAL)).get());
            assertTrue(((Limits)next.supply(ORIGINAL)).environment());
        }
    }
    @Test void rejectsUnavailableReaderChangedHandleAndForeignBudgetThenStaysRejected() {
        var f = new Fixture();
        try (var ignored = f.enter()) {
            f.advance(); f.unavailable = true;
            assertSame(ORIGINAL, f.supply(ORIGINAL));
            f.unavailable = false;
            assertSame(ORIGINAL, f.supply(ORIGINAL));
        }
        var next = new Fixture();
        try (var ignored = next.enter()) {
            next.advance();
            assertSame(ORIGINAL, NativeFixedWingTrimEnvironment.supplement(next.computer, next.body, new Object(), .025,
                    next.state.snapshot(), 4, 5, ORIGINAL, 6));
            assertSame(ORIGINAL, next.supply(ORIGINAL));
        }
        var foreign = new Fixture();
        try (var ignored = foreign.enter()) {
            foreign.advance(); NativeFixedWingTrimEnvironment.captureBudget(new Object(), new Object());
            assertSame(ORIGINAL, foreign.supply(ORIGINAL));
        }
    }
    @Test void preservesKnownNativeEnvironmentAndDisabledScopeDoesNotReadNativeObjects() {
        var f = new Fixture();
        try (var ignored = f.enter()) {
            var original = new Limits(false, 9, 8, true);
            assertSame(original, f.supply(original)); assertEquals(0, f.writes);
        }
        var disabled = new Fixture();
        try (var ignored = NativeFixedWingTrimEnvironment.enter(disabled.computer, disabled.body, disabled.handle, .025, disabled, false)) {
            assertSame(ORIGINAL, disabled.supply(ORIGINAL)); assertEquals(0, disabled.reads);
        }
    }
}
