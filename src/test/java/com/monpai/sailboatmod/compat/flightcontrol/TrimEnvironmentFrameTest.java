package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TrimEnvironmentFrameTest {
    static final Object COMPUTER = new Object(), BODY = new Object(), HANDLE = new Object();
    static TrimEnvironmentFrame.State state(double time) {
        Object snapshot = new Object();
        return new TrimEnvironmentFrame.State(snapshot, new Object(), "body", "body", time, time,
                0, 0, 0, 1, 4, 5, 6, snapshot, true, true, true, true);
    }
    static TrimEnvironmentFrame.State changed(TrimEnvironmentFrame.State original, String name, Object value) {
        try {
            var parts = TrimEnvironmentFrame.State.class.getRecordComponents();
            Object[] args = new Object[parts.length];
            Class<?>[] types = new Class<?>[parts.length];
            for (int i = 0; i < parts.length; i++) {
                types[i] = parts[i].getType();
                args[i] = parts[i].getName().equals(name) ? value : parts[i].getAccessor().invoke(original);
            }
            return TrimEnvironmentFrame.State.class.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    static TrimEnvironmentFrame.Entry entry() {
        return new TrimEnvironmentFrame.Entry(COMPUTER, BODY, HANDLE, state(10), .025);
    }
    static boolean permits(TrimEnvironmentFrame.Entry entry, TrimEnvironmentFrame.State state) {
        return TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, HANDLE, state, state.snapshot(), 4, 5, 6);
    }
    @Test void acceptsOnlyNewModelAndExactNewSnapshotAtNativeTime() {
        assertTrue(permits(entry(), state(10.025)));
    }
    @Test void rejectsChangedEvidenceDimensionsAndUnavailableSources() {
        var entry = entry();
        var current = state(10.025);
        var invalid = Map.ofEntries(
                Map.entry("snapshot", entry.before().snapshot()), Map.entry("model", entry.before().model()),
                Map.entry("bodyId", "another"), Map.entry("structureId", "another"),
                Map.entry("physicsTime", 10.05), Map.entry("clock", 10.05),
                Map.entry("qx", Double.NaN), Map.entry("qw", 2.0),
                Map.entry("topology", 7L), Map.entry("session", 7L), Map.entry("revision", 7L),
                Map.entry("executionVehicle", new Object()), Map.entry("active", false), Map.entry("fresh", false),
                Map.entry("thrust", false), Map.entry("execution", false));
        invalid.forEach((name, value) -> assertFalse(permits(entry, changed(current, name, value)), name));
        for (String field : new String[]{"snapshot", "model", "bodyId", "structureId", "executionVehicle"})
            assertFalse(permits(entry, changed(current, field, null)), field);
    }
    @Test void rejectsDifferentObjectsEvenWhenNativeIdsOrValuesCouldMatch() {
        var entry = entry(); var state = state(10.025);
        assertFalse(TrimEnvironmentFrame.permits(entry, new Object(), BODY, HANDLE, state, state.snapshot(), 4, 5, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, new Object(), HANDLE, state, state.snapshot(), 4, 5, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, new Object(), state, state.snapshot(), 4, 5, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, HANDLE, state, new Object(), 4, 5, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, HANDLE, state, state.snapshot(), 7, 5, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, HANDLE, state, state.snapshot(), 4, 7, 6));
        assertFalse(TrimEnvironmentFrame.permits(entry, COMPUTER, BODY, HANDLE, state, state.snapshot(), 4, 5, 7));
    }
    @Test void rejectsClockReuseNonfiniteTimeAndInvalidDt() {
        for (double dt : new double[]{0, -.025, .251, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(permits(new TrimEnvironmentFrame.Entry(COMPUTER, BODY, HANDLE, state(10), dt), state(10.025)));
        for (double time : new double[]{10, 9.975, Double.NaN, Double.POSITIVE_INFINITY})
            assertFalse(permits(entry(), state(time)));
    }
}
