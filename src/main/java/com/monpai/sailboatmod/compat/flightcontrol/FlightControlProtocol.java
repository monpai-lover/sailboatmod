package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.Objects;
import static com.monpai.sailboatmod.compat.flightcontrol.FlightControlBridge.*;

/** The command transaction is separate from optional-mod reflection. */
final class FlightControlProtocol {
    interface Port {
        TerminalSnapshot snapshot() throws ReflectiveOperationException;
        boolean defaultEnabled() throws ReflectiveOperationException;
        boolean setDefaultEnabled(long revision, boolean value) throws ReflectiveOperationException;
        boolean action(Action action, ExpectedState expected) throws ReflectiveOperationException;
    }
    static Result request(Port port, Action action, ExpectedState expected) throws ReflectiveOperationException {
        TerminalSnapshot before = port.snapshot();
        if (!before.available()) return failure(before.reason(), before);
        if (expected == null || !Objects.equals(expected, before.expected())) return failure("stale_view", before);
        boolean initialDefault = port.defaultEnabled();
        // FC 0.8.3 uses default XOR redstone. A high master signal belongs to the other operator.
        if (initialDefault != before.enabled()) return failure("external_enablement_conflict", before);
        boolean enabling = action == Action.START || action == Action.RESUME;
        if (enabling && !before.linked()) return failure("unlinked", before);
        if (enabling && !before.profileValid()) return failure("invalid_profile", before);
        boolean allowed = switch (action) {
            case START -> is(before.phase(), "READY", "COMPLETED", "BLOCKED");
            case RESUME -> is(before.phase(), "PAUSED", "PREEMPTED");
            case PAUSE -> before.active() || "PAUSED".equals(before.phase());
            case CANCEL -> true;
        };
        if (!allowed) return failure("invalid_transition", before);
        if (initialDefault != enabling && !port.setDefaultEnabled(before.expected().configRevision(), enabling))
            return failure("config_rejected", port.snapshot());
        TerminalSnapshot configured = port.snapshot();
        if (configured.enabled() != enabling) {
            if (enabling && !initialDefault) rollbackEnablement(port, configured.expected().configRevision());
            return failure("external_enablement_conflict", port.snapshot());
        }
        if (action == Action.PAUSE && "PAUSED".equals(configured.phase()))
            return new Result(true, "accepted", configured);
        boolean accepted;
        try {
            accepted = port.action(action, configured.expected());
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (enabling && !initialDefault) rollbackEnablement(port, configured.expected().configRevision());
            throw failure;
        }
        if (!accepted && enabling && !initialDefault) rollbackEnablement(port, configured.expected().configRevision());
        return new Result(accepted, accepted ? "accepted" : "action_rejected", port.snapshot());
    }
    private static void rollbackEnablement(Port port, long revision) throws ReflectiveOperationException {
        TerminalSnapshot now = port.snapshot();
        if (now.expected().configRevision() == revision && port.defaultEnabled())
            port.setDefaultEnabled(revision, false);
    }
    static String submissionProblem(TerminalSnapshot state, boolean defaultEnabled, ExpectedState expected) {
        if (!state.available()) return state.reason();
        if (expected == null || !expected.equals(state.expected())) return "stale_view";
        if (defaultEnabled != state.enabled()) return "external_enablement_conflict";
        if (state.enabled() || state.active()) return "terminal_must_be_paused";
        if (!is(state.phase(), "READY", "BLOCKED", "PAUSED", "PREEMPTED", "COMPLETED")) return "unsupported_phase";
        if (!state.linked()) return "unlinked";
        return "";
    }
    private static boolean is(String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }
    private static Result failure(String reason, TerminalSnapshot snapshot) {
        return new Result(false, reason, snapshot);
    }
}
