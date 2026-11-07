package com.monpai.sailboatmod.compat.flightcontrol;

import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.UUID;

/** Transient own-mod authority only. No native defaults, leases, forces, or saved state are changed here. */
public final class NativeGroundNeutral {
    public static final long LEASE_NANOS = 500_000_000L;
    public static final long ACK_NANOS = 250_000_000L;
    private static final Object LOCK = new Object();
    private static final IdentityHashMap<Object, Entry> ENTRIES = new IdentityHashMap<>();
    private static long generation;
    private static volatile boolean hookSeen;
    private NativeGroundNeutral() { }
    public enum Scope { DRIVE, HOLD }
    public record Context(Object requester, UUID ownerRegistration, Object mission, Object terminal,
            Object body, String profile, long topologyEpoch, long sessionEpoch, Scope scope) {
        public Context(Object requester, UUID ownerRegistration, Object mission, Object terminal,
                Object body, String profile, long topologyEpoch, long sessionEpoch) {
            this(requester,ownerRegistration,mission,terminal,body,profile,topologyEpoch,sessionEpoch,Scope.DRIVE);
        }
    }
    /** Runtime validation only, never restored from NBT. HOLD is not a drive permission. */
    public record HoldProof(Object requester, UUID ownerRegistration, Object mission, UUID physicalId,
            String profile, long validatedAtNanos, boolean parking) { }
    public record PhysicsContext(Object body, String profile, long topologyEpoch, long sessionEpoch,
            double physicsTimeSeconds, boolean normalWork, boolean nativeReleased, boolean noManualForeign) { }
    public static final class Lease {
        private final Object computer;
        private final long generation;
        private Lease(Object computer, long generation) { this.computer = computer; this.generation = generation; }
        public long generation() { return generation; }
    }
    public record Ack(boolean acknowledged, long generation, double physicsTimeSeconds,
            int observations, long observedAtNanos, String reason) { }
    public interface NativeActions {
        void stopControlOutput(boolean sync);
        void clearLinkedSwivelMountLocks(double dt);
        void commandLinkedFlapBearingsNeutral(boolean active);
    }
    private static final class Entry {
        final Lease lease;
        final Context context;
        long renewedAt, observedAt;
        double physicsTime = Double.NaN;
        int observations;
        String reason = "waiting_native_physics";
        Entry(Lease lease, Context context, long now) { this.lease = lease; this.context = context; renewedAt = now; }
    }
    public static Lease request(Object computer, Context context, long nowNanos, boolean eligible) {
        if (!eligible || computer == null || !valid(context)) return null;
        synchronized (LOCK) {
            Entry current = ENTRIES.get(computer);
            if (current != null && expired(current, nowNanos)) { ENTRIES.remove(computer); current = null; }
            if (current != null) {
                if (same(current.context, context)) { current.renewedAt = nowNanos; return current.lease; }
                if (current.context.requester() != context.requester()
                        || !current.context.ownerRegistration().equals(context.ownerRegistration())) return null;
            }
            if (generation == Long.MAX_VALUE) return null;
            Lease lease = new Lease(computer, ++generation);
            ENTRIES.put(computer, new Entry(lease, context, nowNanos));
            return lease;
        }
    }
    public static Ack inspect(Lease lease, Context current, long nowNanos) {
        synchronized (LOCK) {
            Entry entry = lease == null ? null : ENTRIES.get(lease.computer);
            if (entry == null || entry.lease != lease) return unavailable(lease, "no_live_claim");
            if (!same(entry.context, current)) return unavailable(lease, "ground_identity_changed");
            return acknowledgement(entry, nowNanos);
        }
    }
    public static void release(Lease lease) {
        if (lease == null) return;
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(lease.computer);
            if (entry != null && entry.lease == lease) ENTRIES.remove(lease.computer);
        }
    }
    /** True means handled/cancel, including failed owned processing. False preserves normal native allocation. */
    public static boolean onPhysics(Object computer, PhysicsContext current, long nowNanos,
            double dt, NativeActions actions) {
        return process(null, computer, current, nowNanos, dt, actions);
    }
    static boolean process(Lease expected, Object computer, PhysicsContext current, long nowNanos,
            double dt, NativeActions actions) {
        hookSeen = true;
        final Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(computer);
            if (entry == null || expected != null && entry.lease != expected) return false;
            if (expired(entry, nowNanos) || !matches(entry.context, current) || actions == null
                    || !Double.isFinite(dt) || dt <= 1.0E-6) {
                ENTRIES.remove(computer);
                return false;
            }
        }
        // No global registry lock is held while native world/weld/actuator callbacks run.
        boolean failed = false;
        try { actions.stopControlOutput(false); } catch (RuntimeException | LinkageError failure) { failed = true; }
        try { actions.clearLinkedSwivelMountLocks(dt); } catch (RuntimeException | LinkageError failure) { failed = true; }
        try { actions.commandLinkedFlapBearingsNeutral(true); } catch (RuntimeException | LinkageError failure) { failed = true; }
        synchronized (LOCK) {
            // A release/replacement during external processing can never resurrect or acknowledge a claim.
            if (ENTRIES.get(computer) != entry || expired(entry, nowNanos)) return true;
            if (failed) {
                entry.observations = 0; entry.physicsTime = Double.NaN; entry.observedAt = 0;
                entry.reason = "native_neutral_failed";
            } else if (entry.observations == 0 || current.physicsTimeSeconds() > entry.physicsTime) {
                entry.physicsTime = current.physicsTimeSeconds();
                entry.observations = Math.min(2, entry.observations + 1);
                entry.observedAt = nowNanos;
                entry.reason = entry.observations >= 2 ? "" : "waiting_second_native_physics";
            }
        }
        return true;
    }

    record Claim(Lease lease, Context context) { }
    static Claim claim(Object computer) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(computer);
            return entry == null ? null : new Claim(entry.lease, entry.context);
        }
    }
    static void invalidate(Lease expected, String reason) {
        if (expected == null) return;
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(expected.computer);
            if (entry != null && entry.lease == expected) {
                entry.observations = 0; entry.physicsTime = Double.NaN; entry.observedAt = 0; entry.reason = reason;
            }
        }
    }
    static void recordHookSeen() { hookSeen = true; }
    /** Pure diagnostics: no native calls, expiry/reconciliation or claim renewal. */
    public static Ack cachedAck(Object computer) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(computer);
            return entry == null ? unavailable(null, "no_live_claim") : acknowledgement(entry, System.nanoTime());
        }
    }
    public static boolean isHookSeen() { return hookSeen; }
    private static Ack acknowledgement(Entry entry, long now) {
        long age = now - entry.observedAt;
        boolean ready = !expired(entry, now) && entry.observations >= 2 && age >= 0 && age <= ACK_NANOS;
        String reason = expired(entry, now) ? "ground_claim_expired"
                : entry.observations < 2 ? entry.reason : ready ? "" : "native_ack_stale";
        return new Ack(ready, entry.lease.generation, entry.physicsTime, entry.observations, entry.observedAt, reason);
    }
    private static Ack unavailable(Lease lease, String reason) { return new Ack(false, lease == null ? 0 : lease.generation, Double.NaN, 0, 0, reason); }
    private static boolean expired(Entry entry, long now) { return now - entry.renewedAt < 0 || now - entry.renewedAt >= LEASE_NANOS; }
    private static boolean valid(Context c) {
        return c != null && c.requester() != null && c.ownerRegistration() != null && c.mission() != null
                && c.terminal() != null && c.body() != null && c.profile() != null && !c.profile().isBlank()
                && c.topologyEpoch() >= 0 && c.sessionEpoch() >= 0 && c.scope() != null;
    }
    private static boolean same(Context a, Context b) {
        return valid(a) && valid(b) && a.requester() == b.requester() && a.ownerRegistration().equals(b.ownerRegistration())
                && a.mission() == b.mission() && a.terminal() == b.terminal() && a.body() == b.body()
                && a.profile().equals(b.profile()) && a.topologyEpoch() == b.topologyEpoch() && a.sessionEpoch() == b.sessionEpoch()
                && a.scope() == b.scope();
    }
    private static boolean matches(Context claim, PhysicsContext current) {
        return current != null && current.body() == claim.body() && Objects.equals(current.profile(), claim.profile())
                && current.topologyEpoch() == claim.topologyEpoch() && current.sessionEpoch() == claim.sessionEpoch()
                && current.normalWork() && current.nativeReleased() && current.noManualForeign()
                && Double.isFinite(current.physicsTimeSeconds()) && current.physicsTimeSeconds() >= 0;
    }
}
