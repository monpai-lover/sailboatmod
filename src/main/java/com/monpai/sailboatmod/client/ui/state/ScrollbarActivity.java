package com.monpai.sailboatmod.client.ui.state;

/** Monotonic idle deadline for scrollbars; visual interpolation belongs to LDLib2. */
public final class ScrollbarActivity {
    private static final long HOLD_MILLIS = 900;
    private boolean active;
    private long lastActivity;

    public void pulse(long now) {
        active = true;
        lastActivity = now;
    }

    public boolean visible(long now, boolean held) {
        if (held) pulse(now);
        return active && now - lastActivity < HOLD_MILLIS;
    }

    public void reset() {
        active = false;
    }
}
