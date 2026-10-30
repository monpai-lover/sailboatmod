package com.monpai.sailboatmod.transport.air;

/** Pure decisions for recovering a persisted mission without replaying a lost FC runtime lease. */
public final class FlightRecoveryPolicy {
    public enum Guard { WAIT, CONFLICT, CANCEL_REPLAY }
    public enum Resume { REQUIRE_LANDING, ARRIVE, PREFLIGHT }
    private FlightRecoveryPolicy() { }
    /** Paused and profile-pending flights also lose their runtime lease; airport queues have none. */
    public static boolean needsReloadGuard(boolean active, boolean departurePending, boolean returnPending) {
        return active && !departurePending && !returnPending;
    }
    public static Guard guard(boolean sameStructure, boolean cardAvailable, String expected, String installed) {
        if (!sameStructure || !cardAvailable) return Guard.WAIT;
        return expected != null && !expected.isBlank() && expected.equals(installed)
                ? Guard.CANCEL_REPLAY : Guard.CONFLICT;
    }
    public static Resume resume(boolean atDeparture, boolean atDestination) {
        if (atDestination) return Resume.ARRIVE;
        return atDeparture ? Resume.PREFLIGHT : Resume.REQUIRE_LANDING;
    }
}
