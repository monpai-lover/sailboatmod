package com.monpai.sailboatmod.route.water;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.monpai.sailboatmod.route.water.PortBerthValidator.*;

class PortTerrainPriorityTest {
    private static final Cell LIVE_WATER = new Cell(State.WATER, 62, Origin.LIVE);
    private static final Cell NBT_LAND = new Cell(State.LAND, 65, Origin.NBT);
    private static final Cell LOD_WATER = new Cell(State.WATER, 62, Origin.TELLUS);

    @Test void unsavedCanalWinsOverSavedLandAndLod() {
        assertSame(LIVE_WATER, choose(LIVE_WATER, NBT_LAND, false, () -> { fail(); return null; }));
    }
    @Test void savedLandCannotBeOverruledByPredictedWater() {
        assertSame(NBT_LAND, choose(null, NBT_LAND, false, () -> { fail(); return LOD_WATER; }));
    }
    @Test void missingNbtCanUseTellus() { assertSame(LOD_WATER, choose(null, null, true, () -> LOD_WATER)); }
    @Test void failedNbtReadCannotFallBackToPrediction() {
        assertEquals(State.UNKNOWN, choose(null, null, false, () -> { fail(); return LOD_WATER; }).state());
    }
    @Test void loadedButUncapturedTerrainIsStillAuthoritative() {
        assertEquals(State.UNKNOWN, choose(Cell.unknown(), NBT_LAND, true, () -> { fail(); return LOD_WATER; }).state());
    }
    @Test void failedLodIsPending() { assertEquals(State.UNKNOWN, choose(null, null, true, () -> null).state()); }
    @Test void noTellusLeavesUnknown() { assertEquals(State.UNKNOWN, choose(null, null, true, null).state()); }
    @Test void pendingDoesNotPoisonTheNextRequest() {
        assertEquals(State.UNKNOWN, choose(null, null, true, Cell::unknown).state());
        assertSame(LOD_WATER, choose(null, null, true, () -> LOD_WATER));
    }
}
