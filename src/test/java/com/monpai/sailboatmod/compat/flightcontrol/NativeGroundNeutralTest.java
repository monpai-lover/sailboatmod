package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;

class NativeGroundNeutralTest {
    private static final long NOW = 1_000_000_000L;
    private static final double DT = .025;
    private static final class Fixture {
        final Object computer = new Object(), requester = new Object(), mission = new Object(),
                terminal = new Object(), body = new Object();
        final UUID owner = UUID.fromString("24ce8509-a2ea-4f20-bb42-e40f8993457d");
        final Context context = new Context(requester, owner, mission, terminal, body, "canonical-profile", 3, 7);
        Lease claim() { Lease l = request(computer, context, NOW, true); assertNotNull(l, "eligible ground owner needs a transient claim"); return l; }
        PhysicsContext frame(double physicsTime) { return new PhysicsContext(body, context.profile(), 3, 7, physicsTime, true, true, true); }
    }
    /** Models native clear releasing a powered welded flap, then normal neutral servo restoring it. */
    private static final class Aircraft implements NativeActions {
        double propulsionN = 112, weldedTargetDeg = 18, shaftRpm = 32;
        boolean weldedServo = true;
        int normalAllocationRuns;
        final List<String> calls = new ArrayList<>();
        public void stopControlOutput(boolean sync) { assertFalse(sync); propulsionN = 0; weldedServo = false; calls.add("stop"); }
        public void clearLinkedSwivelMountLocks(double dt) { assertEquals(DT, dt); calls.add("locks"); }
        public void commandLinkedFlapBearingsNeutral(boolean active) { weldedTargetDeg = 0; weldedServo = active; calls.add("neutral"); }
        void normalAllocation() { normalAllocationRuns++; propulsionN = 112; }
    }
    private static boolean dispatch(Fixture f, PhysicsContext frame, long now, Aircraft a) {
        boolean cancelled = onPhysics(f.computer, frame, now, DT, a);
        if (!cancelled) a.normalAllocation();
        return cancelled;
    }
    @Test void removesTheObservedCounterThrustAndLeavesPoweredWeldedServoNeutral() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        assertTrue(dispatch(f, f.frame(40), NOW + 1, a));
        assertEquals(0, a.propulsionN); assertTrue(a.weldedServo); assertEquals(0, a.weldedTargetDeg);
        assertEquals(32, a.shaftRpm); assertEquals(0, a.normalAllocationRuns);
        assertEquals(List.of("stop", "locks", "neutral"), a.calls);
        assertFalse(inspect(lease, f.context, NOW + 2).acknowledged(), "one processed physics sample is insufficient");
        assertTrue(dispatch(f, f.frame(40.025), NOW + 3, a));
        var ack = inspect(lease, f.context, NOW + 4);
        assertTrue(ack.acknowledged()); assertEquals(2, ack.observations());
        assertEquals(40.025, ack.physicsTimeSeconds()); assertEquals(lease.generation(), ack.generation());
    }
    @Test void duplicatePhysicsTimestampDoesNotCountAsAnIndependentObservation() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(2), NOW + 1, a); dispatch(f, f.frame(2), NOW + 2, a);
        for (int i = 0; i < 5; i++) {
            var pending = inspect(lease, f.context, NOW + 3);
            assertFalse(pending.acknowledged()); assertEquals(1, pending.observations());
        }
        dispatch(f, f.frame(2.025), NOW + 4, a);
        assertTrue(inspect(lease, f.context, NOW + 5).acknowledged());
    }
    @Test void noLeaseNeverSuppressesOrdinaryNativeAllocation() {
        var f = new Fixture(); var a = new Aircraft();
        assertFalse(dispatch(f, f.frame(8), NOW, a));
        assertEquals(112, a.propulsionN); assertTrue(a.calls.isEmpty()); assertEquals(1, a.normalAllocationRuns);
    }
    @Test void foreignAuthorityIsDetectedOnThePhysicsStepWithoutWaitingForLeaseExpiry() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(1), NOW + 1, a); dispatch(f, f.frame(1.025), NOW + 2, a);
        assertTrue(inspect(lease, f.context, NOW + 3).acknowledged());
        var foreign = new PhysicsContext(f.body, f.context.profile(), 3, 7, 1.05, true, false, false);
        a.calls.clear(); assertFalse(dispatch(f, foreign, NOW + 4, a));
        assertEquals(112, a.propulsionN); assertTrue(a.calls.isEmpty());
        assertFalse(inspect(lease, f.context, NOW + 5).acknowledged());
    }
    @Test void changedBodyProfileNativeEpochOrWorkModeDoesNotNeutralizeAnotherContext() {
        List<java.util.function.Function<Fixture, PhysicsContext>> changes = List.of(
                f -> new PhysicsContext(new Object(), f.context.profile(), 3, 7, 2, true, true, true),
                f -> new PhysicsContext(f.body, "changed-profile", 3, 7, 2, true, true, true),
                f -> new PhysicsContext(f.body, f.context.profile(), 4, 7, 2, true, true, true),
                f -> new PhysicsContext(f.body, f.context.profile(), 3, 8, 2, true, true, true),
                f -> new PhysicsContext(f.body, f.context.profile(), 3, 7, 2, false, true, true));
        for (var change : changes) {
            var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
            dispatch(f, f.frame(1), NOW + 1, a); dispatch(f, f.frame(1.025), NOW + 2, a);
            assertTrue(inspect(lease, f.context, NOW + 3).acknowledged(), "each changed fence starts with its own live acknowledged claim");
            a.calls.clear();
            assertFalse(dispatch(f, change.apply(f), NOW + 4, a));
            assertEquals(112, a.propulsionN); assertTrue(a.calls.isEmpty());
            assertEquals(1, a.normalAllocationRuns);
            assertFalse(inspect(lease, f.context, NOW + 5).acknowledged());
        }
    }
    @Test void expiredClaimCannotSuppressAndAnOldAcknowledgementCannotAuthorizeWheels() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(3), NOW + 1, a); dispatch(f, f.frame(3.025), NOW + 2, a);
        assertTrue(inspect(lease, f.context, NOW + 3).acknowledged());
        assertFalse(inspect(lease, f.context, NOW + ACK_NANOS + 3).acknowledged());
        a.calls.clear(); assertFalse(dispatch(f, f.frame(4), NOW + LEASE_NANOS + 1, a));
        assertTrue(a.calls.isEmpty()); assertEquals(112, a.propulsionN);
    }
    @Test void aNewMissionOrRequesterCannotInheritThePreviousTwoPhysicsAcknowledgements() {
        var f = new Fixture(); var old = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(5), NOW + 1, a); dispatch(f, f.frame(5.025), NOW + 2, a);
        assertTrue(inspect(old, f.context, NOW + 3).acknowledged()); release(old);
        var nextContext = new Context(f.requester, f.owner, new Object(), f.terminal, f.body, f.context.profile(), 3, 7);
        var next = request(f.computer, nextContext, NOW + 4, true); assertNotNull(next);
        assertNotEquals(old.generation(), next.generation());
        release(old); // stale lifecycle callback must not release the replacement generation.
        assertFalse(inspect(next, nextContext, NOW + 5).acknowledged());
        dispatch(f, f.frame(5.05), NOW + 6, a); dispatch(f, f.frame(5.075), NOW + 7, a);
        assertTrue(inspect(next, nextContext, NOW + 8).acknowledged());
        var other = new Context(new Object(), f.owner, nextContext.mission(), f.terminal, f.body, f.context.profile(), 3, 7);
        assertNull(request(f.computer, other, NOW + 9, true), "another RVC object must not replace the live owner");
        assertFalse(inspect(next, other, NOW + 10).acknowledged());
    }
    @Test void partialNativeFailureInvalidatesAcknowledgementAndStillAttemptsFinalFlapNeutral() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(6), NOW + 1, a); dispatch(f, f.frame(6.025), NOW + 2, a);
        assertTrue(inspect(lease, f.context, NOW + 3).acknowledged());
        NativeActions broken = new NativeActions() {
            public void stopControlOutput(boolean sync) { a.stopControlOutput(sync); }
            public void clearLinkedSwivelMountLocks(double dt) { throw new IllegalStateException("native lock failure"); }
            public void commandLinkedFlapBearingsNeutral(boolean active) { a.commandLinkedFlapBearingsNeutral(active); }
        };
        boolean handled = onPhysics(f.computer, f.frame(6.05), NOW + 4, DT, broken);
        if (!handled) a.normalAllocation(); // The old false return would immediately reapply counter-thrust.
        assertTrue(handled, "a failed owned neutral step must still cancel ordinary allocation");
        assertFalse(inspect(lease, f.context, NOW + 5).acknowledged());
        assertEquals(0, a.normalAllocationRuns); assertEquals(0, a.propulsionN);
        assertTrue(a.weldedServo); assertEquals(0, a.weldedTargetDeg);
    }
    @Test void sameOwnerRenewalKeepsGenerationButReleaseImmediatelyRevokesIt() {
        var f = new Fixture(); var lease = f.claim(); var a = new Aircraft();
        dispatch(f, f.frame(7), NOW + 1, a); dispatch(f, f.frame(7.025), NOW + 2, a);
        var renewed = request(f.computer, f.context, NOW + 100_000_000L, true);
        assertSame(lease, renewed); assertTrue(inspect(lease, f.context, NOW + 100_000_001L).acknowledged());
        release(lease); a.calls.clear(); assertFalse(dispatch(f, f.frame(7.05), NOW + 100_000_002L, a));
        assertTrue(a.calls.isEmpty()); assertFalse(inspect(lease, f.context, NOW + 100_000_003L).acknowledged());
    }
    @Test void ineligibleOrIncompleteClaimsRemainNonMutating() {
        var f = new Fixture();
        assertNull(request(f.computer, f.context, NOW, false));
        assertNull(request(null, f.context, NOW, true)); assertNull(request(f.computer, null, NOW, true));
        var invalid = new Context(f.requester, f.owner, f.mission, f.terminal, null, f.context.profile(), 3, 7);
        assertNull(request(f.computer, invalid, NOW, true));
        assertFalse(dispatch(f, f.frame(1), NOW + 1, new Aircraft()));
    }
}
