package com.monpai.sailboatmod.transport.air;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class FlightMissionTest {
    private FlightMission mission() {
        return FlightMission.start(UUID.randomUUID(), "minecraft:overworld", BlockPos.ZERO,
                AircraftClass.FIXED_WING, List.of("shipment-1"), List.of(new Vec3(0, 100, 0),
                        new Vec3(100, 100, 0), new Vec3(200, 70, 0)), 10);
    }
    @Test void physicalFlightCannotAdvanceVirtually() {
        FlightMission m = mission();
        assertFalse(m.advanceVirtual(40, 20));
        assertEquals(0, m.progress());
        assertFalse(m.hasExclusiveStorage());
    }
    @Test void handoffRequiresCustodyAndRejectsStaleGeneration() {
        FlightMission m = mission();
        long generation = m.beginHandoff();
        assertFalse(m.confirmStored(generation, ""));
        assertFalse(m.confirmStored(generation - 1, "storage-1"));
        assertTrue(m.confirmStored(generation, "storage-1"));
        assertFalse(m.confirmStored(generation, "storage-2"));
        assertTrue(m.hasExclusiveStorage());
    }
    @Test void virtualArrivalWaitsForPhysicalRecoveryNeverCompletesDelivery() {
        FlightMission m = mission();
        long generation = m.beginHandoff();
        assertTrue(m.confirmStored(generation, "storage-1"));
        for (int t = 0; t < 100; t++) m.advanceVirtual(20, 10);
        assertEquals(FlightMission.Representation.MATERIALIZING, m.representation());
        assertTrue(m.active());
        assertFalse(m.finishAtAirport(false));
        assertFalse(m.confirmRestored(generation - 1));
        assertTrue(m.confirmRestored(generation));
        assertFalse(m.finishAtAirport(false));
        assertTrue(m.finishAtAirport(true));
    }
    @Test void nbtRoundTripKeepsSingleCustodyAndTraceOwnership() {
        FlightMission m = mission();
        m.confirmStored(m.beginHandoff(), "storage-1");
        m.advanceVirtual(20, 10);
        FlightMission copy = FlightMission.load(m.save());
        assertEquals(m.id(), copy.id());
        assertEquals(m.position(), copy.position());
        assertEquals(m.generation(), copy.generation());
        assertEquals("storage-1", copy.storageRef());
        assertTrue(copy.ownsTrace("shipment-1"));
        assertFalse(copy.ownsTrace("someone-else"));
    }
    @Test void invalidTimeSpeedAndNonFiniteRoutesAreRejected() {
        FlightMission m = mission();
        m.confirmStored(m.beginHandoff(), "storage-1");
        assertFalse(m.advanceVirtual(-20, 10));
        assertFalse(m.advanceVirtual(20, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> FlightMission.start(UUID.randomUUID(),
                "minecraft:overworld", BlockPos.ZERO, AircraftClass.AIRSHIP, List.of(),
                List.of(Vec3.ZERO, new Vec3(Double.NaN, 0, 0)), 1));
    }
    @Test void invalidCustodyIsQuarantinedWithoutErasingRecoveryEvidence() {
        var raw=mission().save(); raw.putString("Representation","VIRTUAL"); raw.putString("StorageRef","");
        var list=new net.minecraft.nbt.ListTag(); list.add(raw);
        var input=new net.minecraft.nbt.CompoundTag(); input.put("Missions",list);
        var saved=FlightMissionSavedData.load(input);
        assertTrue(saved.all().isEmpty());
        assertFalse(saved.hasActiveMissionForTrace("shipment-1"));
        assertEquals(raw,saved.save(new net.minecraft.nbt.CompoundTag(),null).getList("Missions",10).getCompound(0));
    }
}
