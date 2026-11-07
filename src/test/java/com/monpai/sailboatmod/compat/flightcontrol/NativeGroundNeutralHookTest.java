package com.monpai.sailboatmod.compat.flightcontrol;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.compat.flightcontrol.NativeGroundNeutral.*;

/** Typed raw native-shaped records exercise production field capture; no optional native/world classes. */
class NativeGroundNeutralHookTest {
    private static final long NOW = 1_000_000_000L;
    enum State { AVAILABLE, VALID, READY, NONE, COMPLETED }
    record Slot(long slotGeneration, State status, Optional<Object> fingerprint) { }
    record Task(State phase, State blockReason, long slotGeneration, Object profileFingerprint) { }
    record Frame(long topologyEpoch, Map<Object,Object> commands) { }
    record Network(State status, long topologyEpoch, Object computerPosition) { }
    record Vehicle(State status, long sampledNanos, double physicsTimeSeconds) { }
    record Mission(Object plan) { }
    static class Holder { Slot profileSlotSnapshot = new Slot(9, State.VALID, Optional.of("profile")); }
    static final class Terminal extends Holder {
        Task taskSnapshot = new Task(State.READY, State.NONE, 9, "profile");
        Network controlNetworkSnapshot = new Network(State.AVAILABLE, 3, "fcc-position");
        Object activeIdentity, activeLeaseToken, activeRouteBinding, activeComputerPos;
    }
    static final class Broker {
        final Map<Object,Object> leasesByOwner = new HashMap<>(), claims = new HashMap<>();
        final AtomicReference<Frame> effectiveFrame = new AtomicReference<>(new Frame(3, new HashMap<>()));
        long topologyEpoch = 3;
    }
    static final class Computer {
        final Object worldPosition = "fcc-position";
        final Broker navigationLeaseBroker = new Broker();
        Vehicle navigationVehicleStateSnapshot = new Vehicle(State.AVAILABLE, NOW, 12.5);
        long navigationTopologyEpoch = 3, navigationSessionEpoch = 7;
        int mode;
        boolean working = true, workDryRun, debugApplyForces, navigationModeRevocationPending;
    }
    static final class Requester {
        boolean active = true, paused, manualTakeover;
        Mission groundMission;
    }
    static final class Fixture {
        final Object body = new Object(), plan = new Object();
        final Computer computer = new Computer(); final Terminal terminal = new Terminal(); final Requester requester = new Requester();
        final Context context;
        Fixture() {
            requester.groundMission = new Mission(plan);
            context = new Context(requester, UUID.randomUUID(), plan, terminal, body, "profile", 3, 7);
        }
        PhysicsContext read(long now) throws ReflectiveOperationException {
            var result = NativeGroundNeutralHook.captureFields(computer, context, body, now);
            assertNotNull(result, "raw native guard capture must be implemented"); return result;
        }
    }
    @Test void readsCachedFieldsIncludingInheritedProfileWithoutInvokingNativeBusinessMethods() throws Exception {
        var f = new Fixture(); var frame = f.read(NOW + 1);
        assertSame(f.body, frame.body()); assertEquals("profile", frame.profile());
        assertEquals(3, frame.topologyEpoch()); assertEquals(7, frame.sessionEpoch());
        assertEquals(12.5, frame.physicsTimeSeconds());
        assertTrue(frame.normalWork()); assertTrue(frame.nativeReleased()); assertTrue(frame.noManualForeign());
    }
    @Test void acquiredButNotPublishedLeaseCannotHideBehindAnEmptyEffectiveFrame() throws Exception {
        var f = new Fixture(); f.computer.navigationLeaseBroker.leasesByOwner.put("foreign", new Object());
        assertTrue(f.computer.navigationLeaseBroker.effectiveFrame.get().commands().isEmpty());
        assertFalse(f.read(NOW + 1).nativeReleased());
    }
    @Test void everyStoredClaimCommandAndActiveTerminalFieldIndependentlyPreventsReleaseProof() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
                f -> f.computer.navigationLeaseBroker.claims.put("foreign", new Object()),
                f -> f.computer.navigationLeaseBroker.effectiveFrame.get().commands().put("foreign", new Object()),
                f -> f.terminal.activeIdentity = new Object(), f -> f.terminal.activeLeaseToken = new Object(),
                f -> f.terminal.activeRouteBinding = new Object(), f -> f.terminal.activeComputerPos = new Object());
        for (var mutate : mutations) {
            var f = new Fixture(); assertTrue(f.read(NOW + 1).nativeReleased()); mutate.accept(f);
            assertFalse(f.read(NOW + 2).nativeReleased());
        }
    }
    @Test void editedProfileOrSlotGenerationCannotReuseAnEarlierClaimFingerprint() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(
                f -> f.terminal.profileSlotSnapshot = new Slot(9, State.VALID, Optional.of("different")),
                f -> f.terminal.profileSlotSnapshot = new Slot(10, State.VALID, Optional.of("profile")),
                f -> f.terminal.taskSnapshot = new Task(State.READY, State.NONE, 9, "different"));
        for (var mutate : mutations) {
            var f = new Fixture(); assertEquals("profile", f.read(NOW + 1).profile()); mutate.accept(f);
            assertNotEquals("profile", f.read(NOW + 2).profile());
        }
    }
    @Test void takeoverPauseNewMissionAndStalePhysicsSampleAreImmediateNotTtlOnlyChecks() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(f -> f.requester.manualTakeover = true,
                f -> f.requester.paused = true, f -> f.requester.active = false,
                f -> f.requester.groundMission = new Mission(new Object()),
                f -> f.computer.navigationVehicleStateSnapshot = new Vehicle(State.AVAILABLE, NOW - 250_000_001L, 12.5));
        for (var mutate : mutations) {
            var f = new Fixture(); assertTrue(f.read(NOW + 1).noManualForeign()); mutate.accept(f);
            assertFalse(f.read(NOW + 2).noManualForeign());
        }
    }
    @Test void workModeDryRunDebugAndNativeRevocationNeverEnterTheOwnedNeutralMode() throws Exception {
        List<Consumer<Fixture>> mutations = List.of(f -> f.computer.working = false,
                f -> f.computer.mode = 1, f -> f.computer.workDryRun = true,
                f -> f.computer.debugApplyForces = true, f -> f.computer.navigationModeRevocationPending = true);
        for (var mutate : mutations) {
            var f = new Fixture(); assertTrue(f.read(NOW + 1).normalWork()); mutate.accept(f);
            assertFalse(f.read(NOW + 2).normalWork());
        }
    }
}
