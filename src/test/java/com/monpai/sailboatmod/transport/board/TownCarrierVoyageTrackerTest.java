package com.monpai.sailboatmod.transport.board;

import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TownCarrierVoyageTrackerTest {
    private static final TownVoyageSnapshot.Endpoint A = new TownVoyageSnapshot.Endpoint("minecraft:overworld", 1, "town:a", "A");
    private static final TownVoyageSnapshot.Endpoint B = new TownVoyageSnapshot.Endpoint("minecraft:overworld", 2, "town:b", "B");
    private static final List<Vec3> ROUTE = List.of(Vec3.ZERO, new Vec3(100, 0, 0));
    private static final TownCarrierVoyageTracker.GeometryBudget UNLIMITED = (id, points) -> true;

    @Test void failedStartHasNoVoyageAndSuccessfulReturnGetsNewIdentity() {
        var tracker = new TownCarrierVoyageTracker();
        tracker.started(false, A, B);
        assertTrue(tracker.current().isEmpty());
        tracker.started(true, A, B);
        UUID outbound = tracker.current().orElseThrow().legId();
        tracker.observe(true, false, A, B);
        tracker.clearMotion();
        assertEquals(outbound, tracker.current().orElseThrow().legId());
        tracker.invalidateGeometry();
        assertEquals(outbound, tracker.current().orElseThrow().legId());
        tracker.finish();
        tracker.started(true, B, A);
        var returning = tracker.current().orElseThrow();
        assertNotEquals(outbound, returning.legId());
        assertEquals(B, returning.source());
        assertEquals(A, returning.target());
    }

    @Test void delayedReturnHoldHidesTheCompletedVoyageDespiteActiveAndPausedFlags() {
        var tracker = new TownCarrierVoyageTracker();
        tracker.started(true, A, B);
        assertTrue(tracker.observe(true, true, A, B).isEmpty());
        assertTrue(tracker.current().isEmpty());
        tracker.started(true, B, A);
        assertEquals(B, tracker.observe(true, false, B, A).orElseThrow().source());
    }

    @Test void unloadPersistenceRetainsIdentityButStopRemovesIt() {
        var tracker = new TownCarrierVoyageTracker();
        tracker.started(true, A, B);
        UUID id = tracker.current().orElseThrow().legId();
        CompoundTag tag = new CompoundTag();
        tracker.writeTo(tag);
        tracker.clearMotion();
        var restored = new TownCarrierVoyageTracker();
        restored.readFrom(tag);
        assertEquals(id, restored.observe(true, false, A, B).orElseThrow().legId());
        assertTrue(restored.observe(false, false, A, B).isEmpty());
        restored.writeTo(tag);
        assertFalse(tag.contains(TownVoyageBinding.NBT_KEY));
    }

    @Test void etaUsesConfirmedProgressAndDropsTrustOnPauseBackwardStallAndDetour() {
        var tracker = new TownCarrierVoyageTracker();
        assertTrue(tracker.ground(ROUTE, 1, Vec3.ZERO, 0, true, UNLIMITED).isEmpty());
        assertEquals(90, tracker.ground(ROUTE, 1, new Vec3(10,0,0), 10, true, UNLIMITED).orElseThrow());
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(20,0,0), 20, false, UNLIMITED).isEmpty());
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(30,0,0), 30, true, UNLIMITED).isEmpty());
        assertEquals(60, tracker.ground(ROUTE, 1, new Vec3(40,0,0), 40, true, UNLIMITED).orElseThrow());
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(39,0,0), 50, true, UNLIMITED).isEmpty());
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(39,0,0), 60, true, UNLIMITED).isEmpty());
        tracker.invalidateGeometry();
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(50,0,0), 70, true, UNLIMITED).isEmpty());
    }

    @Test void preprocessingIsOncePerGeometryAndDeniedBudgetRetriesAtBoundedCadence() {
        var tracker = new TownCarrierVoyageTracker();
        int[] reservations = {0};
        TownCarrierVoyageTracker.GeometryBudget denied = (id, points) -> { reservations[0]++; return false; };
        tracker.ground(ROUTE, 1, Vec3.ZERO, 0, true, denied);
        tracker.ground(ROUTE, 1, Vec3.ZERO, 40, true, denied);
        assertEquals(1, reservations[0]);
        tracker.ground(ROUTE, 1, Vec3.ZERO, 200, true, denied);
        assertEquals(2, reservations[0]);
        tracker.invalidateGeometry();
        TownCarrierVoyageTracker.GeometryBudget accepted = (id, points) -> { reservations[0]++; return true; };
        tracker.ground(ROUTE, 1, Vec3.ZERO, 240, true, accepted);
        tracker.ground(ROUTE, 1, new Vec3(10,0,0), 280, true, accepted);
        assertEquals(3, reservations[0]);
    }

    @Test void legacyActiveCanAttachWithoutRestartAndIndexZeroCannotEstimate() {
        var tracker = new TownCarrierVoyageTracker();
        assertNotNull(tracker.observe(true, false, A, B).orElseThrow());
        assertTrue(tracker.ground(ROUTE, 0, Vec3.ZERO, 0, true, UNLIMITED).isEmpty());
        assertTrue(tracker.ground(ROUTE, 1, new Vec3(10,0,0), 40, true, UNLIMITED).isEmpty());
    }
}
