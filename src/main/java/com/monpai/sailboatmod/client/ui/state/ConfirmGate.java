package com.monpai.sailboatmod.client.ui.state;

/**
 * Two-step confirmation for destructive actions such as cancelling a listing or an order.
 *
 * <p>The first press arms the gate for one target; a second press on the same target within the window confirms.
 * Pressing a different target re-arms for that target instead, so a refreshed list can never confirm the wrong row.
 * Targets are stable identifiers (listing or order IDs), never list indexes.</p>
 */
public final class ConfirmGate {
    /** Window in which the second press counts: 3 seconds at 20 ticks per second. */
    public static final int DEFAULT_WINDOW_TICKS = 60;

    private final int windowTicks;
    private String armedTarget = "";
    private long armedAt;

    public ConfirmGate() {
        this(DEFAULT_WINDOW_TICKS);
    }

    public ConfirmGate(int windowTicks) {
        this.windowTicks = Math.max(1, windowTicks);
    }

    /**
     * Registers a press on {@code target}.
     *
     * @return {@code true} when this press confirms the action; {@code false} when it only armed the gate.
     */
    public boolean press(String target, long nowTick) {
        if (target == null || target.isBlank()) {
            return false;
        }
        if (isArmed(target, nowTick)) {
            disarm();
            return true;
        }
        armedTarget = target;
        armedAt = nowTick;
        return false;
    }

    public boolean isArmed(String target, long nowTick) {
        return target != null
                && !armedTarget.isEmpty()
                && armedTarget.equals(target)
                && nowTick - armedAt < windowTicks;
    }

    public void disarm() {
        armedTarget = "";
        armedAt = 0L;
    }
}
