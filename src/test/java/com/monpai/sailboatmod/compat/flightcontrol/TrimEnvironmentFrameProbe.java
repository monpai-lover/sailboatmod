package com.monpai.sailboatmod.compat.flightcontrol;

/** Standalone javac/java regression; no Gradle, optional jar, Minecraft or JUnit needed. */
public final class TrimEnvironmentFrameProbe {
    public static void main(String[] args) {
        Object computer = new Object(), body = new Object(), handle = new Object();
        var old = state(new Object(), new Object(), 10, 10);
        var now = state(new Object(), new Object(), 10.025, 10.025);
        var entry = new TrimEnvironmentFrame.Entry(computer, body, handle, old, .025);
        if (!TrimEnvironmentFrame.permits(entry, computer, body, handle, now, now.snapshot(), 4, 5, 6))
            throw new AssertionError("fresh model and exact same native frame must permit environment-only propagation");
        System.out.println("TrimEnvironmentFrameProbe GREEN");
    }
    private static TrimEnvironmentFrame.State state(Object snapshot, Object model, double time, double clock) {
        return new TrimEnvironmentFrame.State(snapshot, model, "body", "body", time, clock,
                0, 0, 0, 1, 4, 5, 6, snapshot, true, true, true, true);
    }
}
