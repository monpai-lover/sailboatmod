package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;
import static org.junit.jupiter.api.Assertions.*;

class FlightControlProtocolTest {
    @Test void rejectsStaleViewBeforeChangingEnablement() throws Exception {
        Port port = new Port("RUNNING", true);
        Result result = FlightControlProtocol.request(port, Action.PAUSE,
                new ExpectedState(2, 99, 7, "profile"));
        assertFalse(result.accepted());
        assertEquals("stale_view", result.reason());
        assertTrue(port.events.isEmpty());
    }

    @Test void pauseDisablesAutomaticResumeBeforeSendingAction() throws Exception {
        Port port = new Port("RUNNING", true);
        Result result = FlightControlProtocol.request(port, Action.PAUSE, port.snapshot().expected());
        assertTrue(result.accepted());
        assertEquals(List.of("enable:false", "action:PAUSE"), port.events);
        assertFalse(result.snapshot().enabled());
        assertEquals("PAUSED", result.snapshot().phase());
    }

    @Test void cancelDisablesAutomaticRestart() throws Exception {
        Port port = new Port("RUNNING", true);
        Result result = FlightControlProtocol.request(port, Action.CANCEL, port.snapshot().expected());
        assertTrue(result.accepted());
        assertEquals(List.of("enable:false", "action:CANCEL"), port.events);
        assertEquals("READY", result.snapshot().phase());
    }

    @Test void verifiedOriginalCardCanBeDisabledBeforeComputerAndProfileSlotInitialize() throws Exception {
        Port port = new Port("READY", true);
        port.linked = false;
        port.profileValid = false;
        Result result = FlightControlProtocol.request(port, Action.CANCEL, port.snapshot().expected());
        assertTrue(result.accepted());
        assertFalse(result.snapshot().enabled());
        assertEquals(List.of("enable:false", "action:CANCEL"), port.events);
    }

    @Test void redstoneXorOverrideIsConflictWithoutWrites() throws Exception {
        Port port = new Port("RUNNING", false);
        port.redstone = true;
        Result result = FlightControlProtocol.request(port, Action.PAUSE, port.snapshot().expected());
        assertFalse(result.accepted());
        assertEquals("external_enablement_conflict", result.reason());
        assertTrue(port.events.isEmpty());
    }

    @Test void rejectedStartRollsBackEnablementSoNextTickCannotStartIt() throws Exception {
        Port port = new Port("READY", false);
        port.reject = true;
        Result result = FlightControlProtocol.request(port, Action.START, port.snapshot().expected());
        assertFalse(result.accepted());
        assertFalse(port.defaultEnabled());
        assertEquals(List.of("enable:true", "action:START", "enable:false"), port.events);
    }

    @Test void enablementConflictRollsBackOurDefaultWithoutChangingTheExternalSignal() throws Exception {
        Port port = new Port("READY", false);
        port.redstoneOnEnable = true;
        Result result = FlightControlProtocol.request(port, Action.START, port.snapshot().expected());
        assertFalse(result.accepted());
        assertEquals("external_enablement_conflict", result.reason());
        assertFalse(port.defaultEnabled());
        assertTrue(port.redstone);
        assertEquals(List.of("enable:true", "enable:false"), port.events);
    }

    @Test void startDoesNotDisguiseMissingControlNetwork() throws Exception {
        Port port = new Port("READY", false);
        port.linked = false;
        Result result = FlightControlProtocol.request(port, Action.START, port.snapshot().expected());
        assertFalse(result.accepted());
        assertEquals("unlinked", result.reason());
        assertTrue(port.events.isEmpty());
    }

    @Test void holdingIsNotCompletedAndUnknownPhasesFailClosed() {
        assertFalse(snapshot("HOLDING", false).completed());
        assertTrue(snapshot("COMPLETED", false).completed());
        assertFalse(snapshot("future_phase", false).completed());
        assertTrue(snapshot("HOLDING", false).active());
    }

    @Test void rejectsNonFiniteCoordinatesAndOversizedRoutes() {
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(Double.NaN, 64, 0));
        assertThrows(IllegalArgumentException.class, () -> new Waypoint(0, Double.POSITIVE_INFINITY, 0));
        assertThrows(IllegalArgumentException.class, () -> new RoutePlan("empty", List.of(),
                Airframe.AIRSHIP, EndBehavior.HOVER, 15, 15));
        assertThrows(IllegalArgumentException.class, () -> new RoutePlan("long",
                java.util.Collections.nCopies(65, new Waypoint(1, 64, 2)),
                Airframe.ROTORCRAFT, EndBehavior.HOVER, 15, 15));
    }

    @Test void fixedWingLandingRequiresFixedWingProfileAndApproachLeg() {
        assertThrows(IllegalArgumentException.class, () -> new RoutePlan("bad",
                List.of(new Waypoint(0, 70, 0), new Waypoint(0, 64, 0)),
                Airframe.AIRSHIP, EndBehavior.APPROACH_LANDING, 15, 15));
        assertThrows(IllegalArgumentException.class, () -> new RoutePlan("no approach",
                List.of(new Waypoint(0, 64, 0)),
                Airframe.FIXED_WING, EndBehavior.APPROACH_LANDING, 15, 15));
    }

    @Test void routeRetainsAltitudeAndOwnsItsWaypointList() {
        List<Waypoint> source = new ArrayList<>(List.of(new Waypoint(1, 120, 2), new Waypoint(3, 80, 4)));
        RoutePlan plan = new RoutePlan("3d", source, Airframe.FIXED_WING,
                EndBehavior.APPROACH_LANDING, 15, 15);
        source.clear();
        assertEquals(120, plan.waypoints().get(0).y());
        assertEquals(80, plan.waypoints().get(plan.waypoints().size() - 1).y());
        assertThrows(UnsupportedOperationException.class, () -> plan.waypoints().clear());
    }

    @Test void submissionRequiresStoppedTerminalAndCurrentRevision() {
        TerminalSnapshot running = snapshot("RUNNING", true);
        assertEquals("terminal_must_be_paused", FlightControlProtocol.submissionProblem(running, true, running.expected()));
        TerminalSnapshot paused = snapshot("PAUSED", false);
        assertEquals("", FlightControlProtocol.submissionProblem(paused, false, paused.expected()));
        assertEquals("stale_view", FlightControlProtocol.submissionProblem(paused, false,
                new ExpectedState(2, 4, 8, "profile")));
    }

    @Test void unknownFuturePhaseCannotAcceptRouteWrites() {
        TerminalSnapshot future = snapshot("FUTURE_PHASE", false);
        assertEquals("unsupported_phase", FlightControlProtocol.submissionProblem(future, false, future.expected()));
    }

    @Test void acceptedPendingSubmissionRequiresTheGeneratedFingerprint() {
        assertThrows(IllegalArgumentException.class,
                () -> new Result(true, "profile_pending", snapshot("READY", false)));
        TerminalSnapshot pending = new TerminalSnapshot(true, "", false, true, false,
                "READY", "NONE", "", null, new ExpectedState(2, 5, 8, ""), null, null, null);
        Result result = new Result(true, "profile_pending", pending, "new-definition");
        assertEquals("", result.snapshot().expected().profileFingerprint());
        assertEquals("new-definition", result.submittedProfileFingerprint());
    }

    static TerminalSnapshot snapshot(String phase, boolean enabled) {
        return new TerminalSnapshot(true, "", enabled, true, true, phase, "NONE", "", null,
                new ExpectedState(2, 4, 7, "profile"), 1, 2, null);
    }

    private static final class Port implements FlightControlProtocol.Port {
        final List<String> events = new ArrayList<>();
        String phase;
        boolean enabled;
        boolean redstone;
        boolean redstoneOnEnable;
        boolean reject;
        boolean linked = true;
        boolean profileValid = true;
        long configRevision = 2;
        Port(String phase, boolean enabled) { this.phase = phase; this.enabled = enabled; }
        @Override public TerminalSnapshot snapshot() {
            return new TerminalSnapshot(true, "", enabled ^ redstone, linked, profileValid, phase, "NONE", "", null,
                    new ExpectedState(configRevision, 4, 7, "profile"), 1, 2, null);
        }
        @Override public boolean defaultEnabled() { return enabled; }
        @Override public boolean setDefaultEnabled(long revision, boolean value) {
            if (revision != configRevision) return false;
            events.add("enable:" + value);
            enabled = value;
            if (value && redstoneOnEnable) redstone = true;
            configRevision++;
            return true;
        }
        @Override public boolean action(Action action, ExpectedState expected) {
            events.add("action:" + action);
            if (reject) return false;
            phase = switch (action) {
                case START, RESUME -> "STARTING";
                case PAUSE -> "PAUSED";
                case CANCEL -> "READY";
            };
            return true;
        }
    }
}
