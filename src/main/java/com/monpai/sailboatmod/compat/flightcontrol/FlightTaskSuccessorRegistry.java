package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.WeakHashMap;

/** Server-scoped, non-persistent proofs of a native internal recovery, never a same-card heuristic. */
public final class FlightTaskSuccessorRegistry {
    public enum Decision { REJECTED, PENDING, ACCEPTED }
    public record Binding(UUID bodyId, String computerPosition, long topology, long sessionEpoch, String controls) { }
    public record State(UUID taskId, String phase, String blockReason, String detail,
                        long configRevision, long slotGeneration, String fingerprint,
                        boolean available, boolean defaultEnabled, boolean effectiveEnabled,
                        Binding binding, boolean leaseOwned, boolean foreignLease) { }
    public record Attempt(Object scope, String terminal, UUID owner, State before, long tick) { }
    private record Pending(UUID previous, UUID next, long tick) { }
    private static final class Claim {
        final UUID owner;
        State state;
        Pending pending;
        Claim(UUID owner, State state) { this.owner = owner; this.state = state; }
    }
    private final Map<Object, Map<String, Claim>> scopes = new WeakHashMap<>();

    public synchronized void claim(Object scope, String terminal, UUID owner, UUID body, State state) {
        if (scope == null || terminal == null || owner == null || body == null || !usable(state)
                || !body.equals(state.binding().bodyId()) || state.taskId() == null
                || !is(state.phase(), "STARTING", "RUNNING", "HOLDING")) return;
        Map<String, Claim> claims = scopes.computeIfAbsent(scope, ignored -> new HashMap<>());
        Claim existing = claims.get(terminal);
        if (existing == null || existing.owner.equals(owner)) claims.put(terminal, new Claim(owner, state));
    }
    public synchronized boolean hasClaim(Object scope, String terminal) { return get(scope, terminal) != null; }
    public synchronized Attempt begin(Object scope, String terminal, State before, long tick) {
        Claim claim = get(scope, terminal);
        if (claim == null || !sameBinding(claim.state, before) || !Objects.equals(claim.state.taskId(), before.taskId())
                || !"PREEMPTED".equals(before.phase()) || !"LEASE_LOST".equals(before.blockReason())
                || !is(before.detail(), "route_lease_renew:STALE_TOKEN", "route_command_publish:STALE_TOKEN")) return null;
        return new Attempt(scope, terminal, claim.owner, before, tick);
    }
    public synchronized void finish(Attempt attempt, State after, long tick) {
        if (attempt == null) return;
        Claim claim = get(attempt.scope(), attempt.terminal());
        if (claim == null || !claim.owner.equals(attempt.owner()) || !sameBinding(claim.state, attempt.before())
                || !Objects.equals(claim.state.taskId(), attempt.before().taskId())
                || tick != attempt.tick() || !sameBinding(attempt.before(), after)
                || !"STARTING".equals(after.phase()) || !"NONE".equals(after.blockReason())
                || after.taskId() == null || after.taskId().equals(attempt.before().taskId())) return;
        claim.pending = new Pending(attempt.before().taskId(), after.taskId(), tick);
    }
    public synchronized Decision consume(Object scope, String terminal, UUID owner, UUID body,
                                          UUID recordedTask, State current, long tick) {
        Claim claim = get(scope, terminal);
        if (claim == null || !claim.owner.equals(owner) || body == null
                || !body.equals(claim.state.binding().bodyId())) return Decision.REJECTED;
        Pending pending = claim.pending;
        if (pending == null || !Objects.equals(pending.previous(), recordedTask)) return Decision.REJECTED;
        if (tick < pending.tick() || tick - pending.tick() > 20 || !sameBinding(claim.state, current)
                || !pending.next().equals(current.taskId())) {
            claim.pending = null;
            return Decision.REJECTED;
        }
        if ("STARTING".equals(current.phase()) && "NONE".equals(current.blockReason())) return Decision.PENDING;
        claim.pending = null;
        if (is(current.phase(), "RUNNING", "HOLDING") && "NONE".equals(current.blockReason()) && current.leaseOwned()) {
            claim.state = current;
            return Decision.ACCEPTED;
        }
        return Decision.REJECTED;
    }
    public synchronized void clear(Object scope, String terminal, UUID owner) {
        Map<String, Claim> claims = scopes.get(scope);
        if (claims != null) {
            Claim claim = claims.get(terminal);
            if (claim != null && claim.owner.equals(owner)) claims.remove(terminal);
            if (claims.isEmpty()) scopes.remove(scope);
        }
    }
    private Claim get(Object scope, String terminal) {
        Map<String, Claim> claims = scopes.get(scope);
        return claims == null ? null : claims.get(terminal);
    }
    private static boolean usable(State state) {
        return state != null && state.available() && state.defaultEnabled() && state.effectiveEnabled()
                && state.configRevision() >= 0 && state.slotGeneration() >= 0
                && state.fingerprint() != null && !state.fingerprint().isBlank()
                && state.binding() != null && state.binding().bodyId() != null
                && state.binding().computerPosition() != null && !state.binding().computerPosition().isBlank()
                && state.binding().topology() >= 0 && state.binding().sessionEpoch() >= 0 && state.binding().controls() != null
                && !state.binding().controls().isBlank() && !state.foreignLease();
    }
    private static boolean sameBinding(State before, State after) {
        return usable(before) && usable(after) && before.configRevision() == after.configRevision()
                && before.slotGeneration() == after.slotGeneration()
                && before.fingerprint().equals(after.fingerprint()) && before.binding().equals(after.binding());
    }
    private static boolean is(String value, String... choices) {
        for (String choice : choices) if (choice.equals(value)) return true;
        return false;
    }
}
