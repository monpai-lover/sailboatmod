package com.monpai.sailboatmod.block.entity;

import java.lang.reflect.Field;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Covers the real guide tick that the controller continues after a paused, unprepared dispatch. */
class RegisteredAircraftRailPreflightTickTest {
    @Test void pausedFuelPreflightWithoutMissionRetainsRejectionWithoutAdmittingMovement() throws Exception {
        var rail = emptyWithReason("fuel_insufficient");
        String saved = rail.save().toString();

        tickWithoutWorldAccess(rail, true, true);
        tickWithoutWorldAccess(rail, true, true);

        assertEquals("fuel_insufficient", rail.reason());
        assertFalse(rail.cancelled());
        assertNull(rail.mission());
        assertNull(rail.pendingDistance());
        assertEquals(saved, rail.save().toString());
    }

    @Test void manuallyPausedTerrainPreflightWithoutMissionKeepsItsWaitReason() throws Exception {
        var rail = emptyWithReason("waiting_airport_terrain");

        tickWithoutWorldAccess(rail, true, true);

        assertEquals("waiting_airport_terrain", rail.reason());
        assertFalse(rail.invalid());
        assertNull(rail.mission());
    }

    @Test void stoppedEmptyGuideTickDoesNotResurrectWorkOrDiscardCancellation() throws Exception {
        var rail = emptyWithReason("fuel_insufficient");
        rail.cancel(null);
        String saved = rail.save().toString();

        tickWithoutWorldAccess(rail, false, false);

        assertTrue(rail.cancelled());
        assertNull(rail.mission());
        assertNull(rail.pendingDistance());
        assertEquals(saved, rail.save().toString());
        assertEquals("fuel_insufficient", rail.reason(),
                "The RVC must ignore this historical reason while idle; the guide does not clear it");
    }

    private static RegisteredAircraftRailController emptyWithReason(String reason) throws Exception {
        var rail = RegisteredAircraftRailController.load(new CompoundTag());
        Field field = RegisteredAircraftRailController.class.getDeclaredField("reason");
        field.setAccessible(true);
        field.set(rail, reason);
        return rail;
    }

    private static void tickWithoutWorldAccess(RegisteredAircraftRailController rail, boolean active, boolean paused) {
        rail.tick(null, active, paused,
                sample -> fail("An unprepared guide must not sample terrain"),
                sample -> fail("An unprepared guide must not perform movement admission"));
    }
}
