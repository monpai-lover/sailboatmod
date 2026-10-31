package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.route.water.PortBerthValidator.*;

class PortBerthValidatorTest {
    private static final Zone ZONE = new Zone(0, 66, 0, -12, 12, -8, 8);
    private static Cell water() { return new Cell(State.WATER, 62, Origin.LIVE); }
    private static Cell land() { return new Cell(State.LAND, 65, Origin.LIVE); }
    private Result check(Samples sample) { return resolve(ZONE, sample, () -> false); }

    @Test void openRealWaterIsVerified() {
        Result r = check((x, z) -> water());
        assertEquals(Verdict.VERIFIED, r.verdict());
        assertEquals(63, r.berth().y());
        assertTrue(Math.hypot(r.berth().x(), r.berth().z()) >= 3.5);
    }
    @Test void dockBlockMayBeOnLand() {
        assertEquals(Verdict.VERIFIED, check((x, z) -> Math.abs(x) < 4 && Math.abs(z) < 4 ? land() : water()).verdict());
    }
    @Test void definitelyDryPortIsInvalid() { assertEquals(Verdict.INVALID, check((x, z) -> land()).verdict()); }
    @Test void unavailableTerrainRemainsListed() {
        Result r = check((x, z) -> Cell.unknown());
        assertEquals(Verdict.PENDING, r.verdict());
        assertTrue(r.listed());
        assertFalse(r.usable());
    }
    @Test void lodWaterIsExplicitlyPredicted() {
        assertEquals(Verdict.PREDICTED, check((x, z) -> new Cell(State.WATER, 62, Origin.TELLUS)).verdict());
    }
    @Test void oneBlockChannelCannotFitBerth() {
        assertEquals(Verdict.INVALID, check((x, z) -> z == 0 ? water() : land()).verdict());
    }
    @Test void twoBlockChannelCanLeave() {
        assertEquals(Verdict.VERIFIED, check((x, z) -> z == 0 || z == 1 ? water() : land()).verdict());
    }
    @Test void smallEnclosedPoolCannotLeave() {
        assertEquals(Verdict.INVALID, check((x, z) -> x >= 8 && x <= 10 && z >= 0 && z <= 2 ? water() : land()).verdict());
    }
    @Test void unknownExitIsNotLand() {
        assertEquals(Verdict.PENDING, check((x, z) -> x >= 8 && x <= 10 && z >= 0 && z <= 2 ? water() : Cell.unknown()).verdict());
    }
    @Test void timeoutNeverHidesPort() {
        Result r = resolve(ZONE, (x, z) -> { fail("expired query must not read terrain"); return land(); }, () -> true);
        assertEquals(Verdict.PENDING, r.verdict());
    }
    @Test void oversizeZoneIsNotFalselyRejectedAfterTruncation() {
        assertEquals(Verdict.PENDING, resolve(new Zone(0, 66, 0, -1000, 1000, -1000, 1000),
                (x, z) -> land(), () -> false).verdict());
    }
    @Test void worksAtNegativeAndChunkBoundaryCoordinates() {
        Result r = resolve(new Zone(-50127, 66, 32258, -12, 12, -8, 8), (x, z) -> water(), () -> false);
        assertTrue(r.usable());
        assertTrue(r.berth().x() < 0);
        assertTrue(r.berth().z() > 0);
    }
    @Test void knownPathCanSucceedDespiteUnrelatedUnknownCells() {
        assertTrue(check((x, z) -> z >= 0 && z <= 3 ? water() : Cell.unknown()).usable());
    }
    @Test void mixedSurfaceHeightsCannotMakeASquareBerth() {
        assertEquals(Verdict.INVALID, check((x, z) -> new Cell(State.WATER, (x & 1) == 0 ? 62 : 80, Origin.NBT)).verdict());
    }
}
