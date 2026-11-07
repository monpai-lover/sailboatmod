package com.monpai.sailboatmod.transport.air;

import com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge;

/** FC persists its profile, but not a running/paused task lease across a world reload. */
public final class FlightResumePolicy {
    private FlightResumePolicy() { }
    public static boolean waitsForAirport(String reason) {
        return "airport_unavailable".equals(reason) || "airport_reserved".equals(reason)
                || "waiting_airport_metadata".equals(reason) || "waiting_airport_terrain".equals(reason)
                || "waiting_ground_surface_reference".equals(reason);
    }
    public static FlightControlBridge.Action action(String phase) {
        if (phase == null) return null;
        return switch (phase) {
            // READY has no live lease. Its START must pass the controller's loading preflight.
            case "READY" -> null;
            case "PAUSED", "PREEMPTED" -> FlightControlBridge.Action.RESUME;
            default -> null;
        };
    }
}
