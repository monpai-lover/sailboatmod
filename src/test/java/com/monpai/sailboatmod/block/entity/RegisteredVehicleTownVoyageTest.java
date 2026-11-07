package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.transport.air.airport.GroundAssignment;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftMission;
import com.monpai.sailboatmod.transport.air.rail.RailAircraftPath;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegisteredVehicleTownVoyageTest {
    private static final BlockPos A = new BlockPos(0, 64, 0), B = new BlockPos(100, 64, 0);

    @Test void completedOutboundMustNotMasqueradeAsTheQueuedEmptyReturn() {
        var old = mission(true);
        UUID preparation = UUID.randomUUID();
        String before = old.save().toString();
        var row = RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, true, true, preparation, old, A, A, B);
        assertNotNull(row);
        assertEquals(preparation, row.legId());
        assertNotEquals(old.id(), row.legId());
        assertEquals(B, row.source());
        assertEquals(A, row.target());
        assertFalse(row.timed(), "preflight has no confirmed flight progress to estimate");
        assertEquals(before, old.save().toString(), "observation cannot change the completed journal");
    }

    @Test void unfinishedMissionUsesItsActualEndpointsAndIdentity() {
        var actual = mission(false);
        String before = actual.save().toString();
        var row = RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, false, false, UUID.randomUUID(), actual, B, A, null);
        assertNotNull(row);
        assertEquals(actual.id(), row.legId());
        assertEquals(A, row.source());
        assertEquals(B, row.target());
        assertTrue(row.timed());
        assertEquals(before, actual.save().toString());
    }

    @Test void ordinaryDeparturePreflightUsesTheCurrentPreparation() {
        UUID preparation = UUID.randomUUID();
        var row = RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, true, false, preparation, null, A, B, null);
        assertNotNull(row);
        assertEquals(preparation, row.legId());
        assertEquals(A, row.source());
        assertEquals(B, row.target());
        assertFalse(row.timed());
    }

    @Test void idleOrCompletedWithoutNewPreflightHasNoCurrentVoyage() {
        assertNull(RegisteredVehicleControllerBlockEntity.townRailIntent(
                false, true, true, UUID.randomUUID(), mission(true), A, B, B));
        assertNull(RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, false, false, UUID.randomUUID(), mission(true), A, B, null));
        assertNull(RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, false, false, UUID.randomUUID(), null, A, B, null));
    }

    @Test void missingPreparationCannotBorrowAnOldMissionForTheNewTrip() {
        assertNull(RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, true, true, null, mission(false), A, A, B));
    }


    @Test void proposedFlightStepNeverBecomesTheBoardConfirmedProgress() {
        var actual = mission(false);
        var proposed = actual.proposeStep(true);
        assertTrue(proposed.targetDistance() > actual.distance());
        var progress = RegisteredVehicleControllerBlockEntity.townRailProgress(true, actual);
        assertNotNull(progress);
        assertEquals(actual.path(), progress.path());
        assertEquals(0, progress.confirmedDistance());
        assertEquals(0, progress.actualStandTicks());
        assertTrue(actual.commitStep(proposed, true));
        assertEquals(actual.distance(), RegisteredVehicleControllerBlockEntity.townRailProgress(true, actual).confirmedDistance());
    }

    @Test void preflightAndCompletedMissionHaveNoTrustedRailEtaInput() {
        assertNull(RegisteredVehicleControllerBlockEntity.townRailProgress(false, mission(false)));
        assertNull(RegisteredVehicleControllerBlockEntity.townRailProgress(true, mission(true)));
        assertNull(RegisteredVehicleControllerBlockEntity.townRailProgress(true, null));
    }

    @Test void realReturnSourceUsesItsActualArrivalBindingAndLegacyReturnDoesNotGuess() {
        var c = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", 1, "town:c", "C");
        var d = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", 2, "town:d", "D");
        var binding = new com.monpai.sailboatmod.transport.board.TownVoyageBinding();
        binding.startSuccessfulVoyage(d, c);
        assertEquals(d, RegisteredVehicleControllerBlockEntity.townGroundSource(true, binding.current(), c));
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint.unknown(),
                RegisteredVehicleControllerBlockEntity.townGroundSource(true, java.util.Optional.empty(), c));
        assertEquals(c, RegisteredVehicleControllerBlockEntity.townGroundSource(false, java.util.Optional.empty(), c));
    }


    @Test void earlyTerrainRecoveryAndPhysicalUnavailabilityKeepTheCurrentIntentWithoutEta() {
        var actual = mission(false);
        assertNotNull(RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, false, false, UUID.randomUUID(), actual, A, B, null));
        var waiting = RegisteredVehicleControllerBlockEntity.townObservationState(true, false, false,
                com.monpai.sailboatmod.transport.control.ControllerStatus.WAITING_TERRAIN, "terrain_pending", false);
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.WAITING_TERRAIN, waiting);
        assertNull(RegisteredVehicleControllerBlockEntity.townRailProgress(false, actual));
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.RECOVERING,
                RegisteredVehicleControllerBlockEntity.townObservationState(true, true, true,
                        com.monpai.sailboatmod.transport.control.ControllerStatus.PAUSED, "recovery_review", false));
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.UNLOADED,
                RegisteredVehicleControllerBlockEntity.townObservationState(false, false, false,
                        com.monpai.sailboatmod.transport.control.ControllerStatus.RUNNING, "", false));
    }

    @Test void terminalSettlingWaitsForArrivalWithoutAnEtaButPassingWaypointsCanMove() {
        var running = com.monpai.sailboatmod.transport.control.ControllerStatus.RUNNING;
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.WAITING_RESOURCE,
                RegisteredVehicleControllerBlockEntity.townObservationState(true, false, false, running, "arrived", true));
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.MOVING,
                RegisteredVehicleControllerBlockEntity.townObservationState(true, false, false, running, "arrived", false));
        assertEquals(com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.State.PAUSED,
                RegisteredVehicleControllerBlockEntity.townObservationState(true, false, true, running, "arrived", true));
    }


    @Test void nativePhaseTasksAndAcceptedSuccessorsDoNotReplaceTheCurrentVoyageIdentity() {
        var tracker = new com.monpai.sailboatmod.transport.board.TownCarrierVoyageTracker();
        var source = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", A.asLong(), "town:a", "A");
        var target = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", B.asLong(), "town:b", "B");
        tracker.started(true, source, target);
        UUID bindingId = tracker.current().orElseThrow().legId();
        for (UUID phaseTask : new UUID[] {null, UUID.randomUUID(), UUID.randomUUID(), null}) {
            tracker.clearMotion(); // Pause/resume preserves the current actual voyage.
            assertEquals(bindingId, RegisteredVehicleControllerBlockEntity.townVoyageLegId(bindingId, null, phaseTask));
        }
        tracker.finish();
        tracker.started(true, target, source);
        UUID returnId = tracker.current().orElseThrow().legId();
        assertNotEquals(bindingId, returnId);
        assertEquals(returnId, RegisteredVehicleControllerBlockEntity.townVoyageLegId(returnId, null, UUID.randomUUID()));
    }

    @Test void nativePhaseTaskClearedOnReloadPreservesThePersistedActualVoyageIdentity() {
        var tracker = new com.monpai.sailboatmod.transport.board.TownCarrierVoyageTracker();
        var source = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", A.asLong(), "town:a", "A");
        var target = new com.monpai.sailboatmod.transport.board.TownVoyageSnapshot.Endpoint("minecraft:overworld", B.asLong(), "town:b", "B");
        tracker.started(true, source, target);
        UUID bindingId = tracker.current().orElseThrow().legId();
        UUID publishedBeforeReload = RegisteredVehicleControllerBlockEntity.townVoyageLegId(bindingId, null, UUID.randomUUID());
        var saved = new net.minecraft.nbt.CompoundTag();
        tracker.writeTo(saved);
        var restored = new com.monpai.sailboatmod.transport.board.TownCarrierVoyageTracker();
        restored.readFrom(saved);
        var leg = restored.observe(true, false, source, target).orElseThrow();
        assertEquals(bindingId, leg.legId());
        assertEquals(publishedBeforeReload, RegisteredVehicleControllerBlockEntity.townVoyageLegId(leg.legId(), null, null));
    }

    @Test void railPreparationAndMissionKeepTheirActualNativeIdentity() {
        UUID preparationId = UUID.randomUUID(), bindingId = UUID.randomUUID();
        var queued = RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, true, false, preparationId, null, A, B, null);
        assertEquals(preparationId, RegisteredVehicleControllerBlockEntity.townVoyageLegId(bindingId, queued, UUID.randomUUID()));
        var actual = mission(false);
        var flying = RegisteredVehicleControllerBlockEntity.townRailIntent(
                true, false, false, preparationId, actual, A, B, null);
        assertEquals(actual.id(), RegisteredVehicleControllerBlockEntity.townVoyageLegId(bindingId, flying, UUID.randomUUID()));
    }

    private static RailAircraftMission mission(boolean complete) {
        var path = RailAircraftPath.compile(List.of(new RailAircraftPath.Leg(
                RailAircraftPath.Phase.CRUISE, List.of(new Vec3(0, 80, 0), new Vec3(10, 80, 0)),
                10, 0, true)), -90);
        var assignment = new GroundAssignment(1, "runway", "stand", "out", "in");
        var mission = RailAircraftMission.start(UUID.randomUUID(), UUID.randomUUID(), "minecraft:overworld",
                A, assignment, B, assignment, path);
        if (complete) {
            while (mission.distance() < path.length()) assertTrue(mission.commitStep(mission.proposeStep(true), true));
            for (int i = 0; i < RailAircraftMission.REQUIRED_STAND_TICKS; i++) mission.observeStand(i, true);
            assertTrue(mission.complete());
        }
        return mission;
    }
}
