package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;
import com.monpai.sailboatmod.transport.air.AircraftClass;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AirportPersistenceTest {
    private static AirportFacility runway() { return new AirportFacility("main", AirportFacility.Type.RUNWAY, new BlockPos(0, 80, 0), new BlockPos(48,80,0), 7, 12); }
    @Test void registrationGeometryRoundTripsWithoutFlatteningAltitude() {
        AirportFacility original = runway();
        assertEquals(original, AirportFacility.load(original.save()));
        assertEquals(80, AirportFacility.load(original.save()).landingPosition().y);
    }
    @Test void terminalDirectoryPersistsDimensionOwnerTownAndEveryFacility() {
        AirportSavedData data = new AirportSavedData();
        BlockPos pos = new BlockPos(5, 72, 9);
        var snapshot = new AirportSavedData.AirportSnapshot("minecraft:overworld", pos, "山顶机场", "town", "owner", List.of(runway()));
        data.put(snapshot);
        data.put(new AirportSavedData.AirportSnapshot("minecraft:the_nether", pos, "Nether", "other", "owner", List.of()));
        AirportSavedData reloaded = AirportSavedData.load(data.save(new CompoundTag(), null));
        assertEquals(snapshot, reloaded.airport("minecraft:overworld", pos));
        assertEquals(List.of(snapshot), reloaded.airports("minecraft:overworld"));
        reloaded.remove("minecraft:overworld", pos);
        assertEquals(1, reloaded.airports().size());
        assertNotNull(reloaded.airport("minecraft:the_nether", pos));
    }
    @Test void fixedWingCannotUseHelipadAndAirshipNeedsMooring() {
        var pad = new AirportFacility("pad", AirportFacility.Type.HELIPAD, new BlockPos(0,80,0), new BlockPos(0,80,0), 7, 12);
        var mooring = new AirportFacility("mast", AirportFacility.Type.MOORING, new BlockPos(0,80,0), new BlockPos(0,80,0), 12, 24);
        assertTrue(runway().supports(AircraftClass.FIXED_WING));
        assertTrue(pad.supports(AircraftClass.ROTORCRAFT));
        assertFalse(pad.supports(AircraftClass.FIXED_WING));
        assertFalse(runway().supports(AircraftClass.AIRSHIP));
        assertTrue(mooring.supports(AircraftClass.AIRSHIP));
        assertFalse(mooring.supports(AircraftClass.ROTORCRAFT));
    }
    @Test void fullHullMustFitInsteadOfMerelyIntersectingLandingZone() {
        var pad = new AirportFacility("pad", AirportFacility.Type.HELIPAD, new BlockPos(0,80,0), new BlockPos(0,80,0), 7, 12);
        assertFalse(pad.isParked(new AABB(-4,80,-1,2,82,2), 0));
        assertFalse(pad.isParked(new AABB(-1,80,-1,2,95,2), 0));
        assertFalse(pad.isParked(new AABB(-1,79,-1,2,82,2), 0));
        assertFalse(pad.isParked(new AABB(-1,80,-1,2,82,2), Double.POSITIVE_INFINITY));
    }
    @Test void nearIntegerLimitsRejectInsteadOfOverflowingScanLoops() {
        var invalid = new AirportFacility("bad", AirportFacility.Type.RUNWAY, new BlockPos(Integer.MAX_VALUE-48,80,0), new BlockPos(Integer.MAX_VALUE,80,0), 7, 12);
        assertEquals(AirportValidation.Result.INVALID_GEOMETRY, AirportValidation.validate(invalid, p -> { throw new AssertionError(); }, p -> true, p -> true));
    }
    @Test void farUnknownCellTakesPriorityBeforeAnyTerrainReads() {
        assertEquals(AirportValidation.Result.UNLOADED, AirportValidation.validate(runway(), p -> p.getX() < 25,
                p -> { throw new AssertionError("unknown facility must not query terrain"); },
                p -> { throw new AssertionError("unknown facility must not query support"); }));
    }
    @Test void diagonalRunwayChecksCellsTouchedOnlyByItsEdge() {
        var runway = new AirportFacility("diagonal", AirportFacility.Type.RUNWAY, new BlockPos(0,80,0), new BlockPos(32,80,32), 7, 12);
        // Centre lies outside the strip, but the block's near corner clips the usable clearance.
        BlockPos edge = new BlockPos(10,82,15);
        assertFalse(runway.containsHorizontal(edge.getX()+.5, edge.getZ()+.5, 0));
        assertEquals(AirportValidation.Result.OBSTRUCTED, AirportValidation.validate(runway, p -> true, p -> !p.equals(edge), p -> true));
    }
    @Test void expiredLeaseDoesNotSurviveRenewalByNewAircraft() {
        AirportReservationBook book = new AirportReservationBook();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        assertTrue(book.reserve("main", first, 10, 20));
        assertTrue(book.reserve("main", first, 20, 20));
        assertFalse(book.available("main", second, 35));
        book.release(first);
        assertTrue(book.reserve("main", second, 35, 20));
        book.release(first);
        assertFalse(book.available("main", first, 36));
        assertTrue(book.available("main", first, 55));
        assertFalse(book.reserve("other", first, Long.MAX_VALUE-1, 10));
        assertFalse(book.reserve("other", first, 0, AirportReservationBook.MAX_TTL_TICKS+1));
    }
    @Test void incrementalValidationNeverExceedsItsBudgetOrDeclaresPartialScanSafe() {
        AirportValidation.Scan scan = new AirportValidation.Scan(runway());
        assertEquals(AirportValidation.Result.WAITING, scan.advance(17, p -> true, p -> true, p -> true));
        assertEquals(17, scan.stepsUsed());
        AirportValidation.Result result = AirportValidation.Result.WAITING;
        for (int i = 0; i < 100 && result == AirportValidation.Result.WAITING; i++) {
            result = scan.advance(2048, p -> true, p -> true, p -> true);
            assertTrue(scan.stepsUsed() <= 2048);
        }
        assertEquals(AirportValidation.Result.VALID, result);
    }
    @Test void fixedWingChecksClearanceBeyondBothRunwayEnds() {
        for (BlockPos obstacle : List.of(new BlockPos(-12,85,0), new BlockPos(60,85,0))) {
            assertFalse(runway().containsHorizontal(obstacle.getX()+.5, obstacle.getZ()+.5, 0));
            assertEquals(AirportValidation.Result.OBSTRUCTED, AirportValidation.validate(runway(), p -> true, p -> !p.equals(obstacle), p -> true));
        }
    }
    @Test void approachClearanceDoesNotRequireAnExtendedGroundRunway() {
        AirportFacility facility = runway();
        assertEquals(AirportValidation.Result.VALID, AirportValidation.validate(facility, p -> true, p -> true,
                p -> facility.containsHorizontal(p.getX()+.5, p.getZ()+.5, 0)));
    }
    @Test void chunksUnloadingBetweenScanPhasesCannotPass() {
        AirportValidation.Scan scan = new AirportValidation.Scan(runway());
        assertEquals(AirportValidation.Result.WAITING, scan.advance(10, p -> true, p -> true, p -> true));
        assertEquals(AirportValidation.Result.UNLOADED, scan.advance(100000, p -> false,
                p -> { throw new AssertionError("unloaded blocks must not be read"); }, p -> true));
    }
}
