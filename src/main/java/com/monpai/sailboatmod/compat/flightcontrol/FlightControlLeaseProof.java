package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.Objects;

/** Pure checks on an immutable copy of the native broker frame; never renews or expires leases. */
final class FlightControlLeaseProof {
    record Identity(UUID body, UUID task, long terminal, UUID planningSession) { }
    record Entry(Object domain, Identity identity, long generation, long deadline) { }
    record Evidence(boolean owned, boolean foreign) { }
    record Activity(Object identity, Object token, Object route, Object computer) {
        boolean empty() { return identity == null && token == null && route == null && computer == null; }
        boolean same(Activity other) {
            return other != null && identity == other.identity && token == other.token
                    && route == other.route && computer == other.computer;
        }
    }
    /** Counts include expired stored leases/commands; observation never expires or reconciles them. */
    record NativeOccupancy(int bundles, int leases, int claims, int commands) {
        boolean known() {
            return bundles >= 0 && bundles <= 128 && bundles == leases && claims >= 0 && claims <= 512
                    && commands == claims && (leases == 0 ? claims == 0 : claims >= leases);
        }
        boolean empty() { return bundles == 0 && leases == 0 && claims == 0 && commands == 0; }
    }
    record AuthorityObservation(Object terminal, Object computer, Object broker, Object frame,
            UUID body, UUID actualBody, Object physicalBody, String dimension, long terminalPosition, long computerPosition, long topology,
            long sessionEpoch, long configRevision, long slotGeneration, long taskRevision,
            UUID task, String fingerprint, boolean available, boolean linked, Activity activity) {
        boolean same(AuthorityObservation other) {
            return other != null && available && other.available && linked && other.linked
                    && terminal != null && computer != null && broker != null && frame != null
                    && terminal == other.terminal && computer == other.computer && broker == other.broker && frame == other.frame
                    && body != null && body.equals(actualBody) && body.equals(other.body) && body.equals(other.actualBody)
                    && physicalBody != null && physicalBody == other.physicalBody
                    && dimension != null && !dimension.isBlank() && dimension.equals(other.dimension)
                    && terminalPosition == other.terminalPosition && computerPosition == other.computerPosition
                    && topology >= 0 && topology == other.topology && sessionEpoch >= 0 && sessionEpoch == other.sessionEpoch
                    && configRevision >= 0 && configRevision == other.configRevision && slotGeneration >= 0 && slotGeneration == other.slotGeneration
                    && taskRevision >= 0 && taskRevision == other.taskRevision && Objects.equals(task, other.task)
                    && fingerprint != null && fingerprint.equals(other.fingerprint) && activity != null && activity.same(other.activity);
        }
    }
    static FlightControlBridge.ControlOwnership control(AuthorityObservation before, AuthorityObservation after,
            NativeOccupancy occupancy, boolean oldFlightConsistent, boolean oldOwned, boolean foreign) {
        boolean observed = before != null && before.same(after) && occupancy != null && occupancy.known();
        boolean empty = observed && occupancy.empty();
        boolean available = observed && (!empty || before.activity().empty());
        // Flight custody still needs every pre-existing ready/identity/slot/profile consistency check.
        boolean owned = available && !empty && oldFlightConsistent && oldOwned && !foreign;
        return new FlightControlBridge.ControlOwnership(available, available ? "" : "control_observation_changed",
                before == null ? null : before.body(), before == null ? null : before.task(),
                before == null ? "" : before.fingerprint(), owned, !available || !empty, !available || foreign);
    }
    static Evidence inspect(Identity active, Long tokenGeneration, Set<?> requiredDomains, List<Entry> frame,
                            UUID body, UUID task, long terminal, boolean sameComputer, long now) {
        boolean foreign = frame.stream().anyMatch(entry -> entry.deadline() - now > 0
                && (entry.identity() == null || !Objects.equals(entry.identity().body(), body)
                || !Objects.equals(entry.identity().task(), task) || entry.identity().terminal() != terminal
                || (active != null && !active.equals(entry.identity()))));
        boolean owned = sameComputer && body != null && task != null && active != null
                && body.equals(active.body()) && task.equals(active.task())
                && active.terminal() == terminal && active.planningSession() != null
                && tokenGeneration != null && !requiredDomains.isEmpty() && !foreign;
        if (owned) for (Object domain : requiredDomains) {
            boolean covered = frame.stream().anyMatch(entry -> Objects.equals(domain, entry.domain())
                    && active.equals(entry.identity()) && entry.generation() == tokenGeneration
                    && entry.deadline() - now > 0);
            if (!covered) { owned = false; break; }
        }
        return new Evidence(owned, foreign);
    }
}
