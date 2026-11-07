package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TrimEnvironmentDiagnosticsTest {
    private record Limits(boolean known) { }
    private static final class Fixture implements NativeFixedWingTrimEnvironment.Access {
        final Object computer = new Object(), body = new Object(), handle = new Object();
        TrimEnvironmentFrame.State state = TrimEnvironmentFrameTest.state(10);
        public TrimEnvironmentFrame.State read(Object c, Object b, Object h) { return state; }
        public boolean known(Object value) { return ((Limits)value).known(); }
        public Object supplement(Object value, Object model, List<Object> budgets, TrimEnvironmentFrame.State state) { return new Limits(true); }
        NativeFixedWingTrimEnvironment.Scope enter() { return NativeFixedWingTrimEnvironment.enter(computer, body, handle, .025, this, true); }
        void publish() {
            NativeFixedWingTrimEnvironment.supplement(computer, body, handle, .025, state.snapshot(), 4, 5, new Limits(false), 6);
        }
    }
    private static Object diagnostics(Object computer) throws Exception {
        return NativeFixedWingTrimEnvironment.class.getMethod("diagnostics", Object.class).invoke(null, computer);
    }
    private static Object value(Object record, String name) throws Exception { return record.getClass().getMethod(name).invoke(record); }

    @Test void observesOnlyTheExactComputerAndActualPostSampleTime() throws Exception {
        var first = new Fixture();
        try (var scope = first.enter()) { first.state = TrimEnvironmentFrameTest.state(10.025); first.publish(); }
        Object before = diagnostics(first.computer);
        assertNotNull(before);
        assertEquals(1L, value(before, "prepared"));
        assertEquals(1L, value(before, "attempted"));
        assertEquals(1L, value(before, "supplemented"));
        assertEquals("body", value(before, "bodyId"));
        assertEquals(10.025, value(before, "physicsTime"));
        var second = new Fixture();
        try (var scope = second.enter()) { second.publish(); }
        Object rejected = diagnostics(second.computer);
        assertEquals(0L, value(rejected, "supplemented"));
        assertEquals("frame_guard_rejected", value(rejected, "reason"));
        assertEquals(before, diagnostics(first.computer));
        assertNull(diagnostics(new Object()));
        assertNull(diagnostics(null));
    }

    @Test void diagnosticsAreBoundedAndNeverAuthorizeDisabledScopes() throws Exception {
        var computers = new ArrayList<Object>();
        for (int i = 0; i < 160; i++) {
            Object computer = new Object(); computers.add(computer);
            try (var scope = NativeFixedWingTrimEnvironment.enter(computer, new Object(), new Object(), .025, null, false)) {
                Limits original = new Limits(false);
                assertSame(original, NativeFixedWingTrimEnvironment.supplement(computer, new Object(), 4, 5, original, 6));
            }
        }
        long present = 0;
        for (Object computer : computers) {
            Object observed = diagnostics(computer);
            if (observed != null) {
                present++;
                assertEquals(0L, value(observed, "supplemented"));
                assertTrue(Double.isNaN((Double)value(observed, "physicsTime")));
            }
        }
        assertTrue(present > 0 && present <= 128);
    }
}
