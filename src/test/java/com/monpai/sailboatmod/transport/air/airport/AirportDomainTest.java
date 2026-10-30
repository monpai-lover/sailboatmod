package com.monpai.sailboatmod.transport.air.airport;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class AirportDomainTest {
    private static final String PACKAGE = "com.monpai.sailboatmod.transport.air.airport.";
    private Object facility(String type, BlockPos end) throws Exception {
        Class<?> kind = Class.forName(PACKAGE + "AirportFacility$Type");
        Object value = java.util.Arrays.stream(kind.getEnumConstants()).filter(v -> v.toString().equals(type)).findFirst().orElseThrow();
        return Class.forName(PACKAGE + "AirportFacility").getConstructor(String.class, kind, BlockPos.class, BlockPos.class, int.class, int.class)
                .newInstance("main", value, new BlockPos(0, 64, 0), end, 7, 12);
    }
    private String validate(Object facility, Predicate<BlockPos> loaded, Predicate<BlockPos> clear, Predicate<BlockPos> supported) throws Exception {
        return Class.forName(PACKAGE + "AirportValidation").getMethod("validate", facility.getClass(), Predicate.class, Predicate.class, Predicate.class)
                .invoke(null, facility, loaded, clear, supported).toString();
    }
    @Test void loadedFlatRunwayIsUsable() throws Exception {
        assertEquals("VALID", validate(facility("RUNWAY", new BlockPos(40,64,0)), p -> true, p -> true, p -> true));
    }
    @Test void unknownChunksNeverReadTerrainOrBecomeUsable() throws Exception {
        assertEquals("UNLOADED", validate(facility("RUNWAY", new BlockPos(40,64,0)), p -> false,
                p -> { throw new AssertionError("must not read unknown terrain"); }, p -> { throw new AssertionError("must not read unknown support"); }));
    }
    @Test void runwayRequiresEverySupportCellAndClearanceCell() throws Exception {
        Object runway = facility("RUNWAY", new BlockPos(40,64,0));
        assertEquals("UNSUPPORTED", validate(runway, p -> true, p -> true, p -> !p.equals(new BlockPos(12,63,1))));
        assertEquals("OBSTRUCTED", validate(runway, p -> true, p -> !p.equals(new BlockPos(12,67,1)), p -> true));
    }
    @Test void shortOrSlopingRunwayIsRejected() throws Exception {
        assertEquals("INVALID_GEOMETRY", validate(facility("RUNWAY", new BlockPos(10,64,0)), p -> true, p -> true, p -> true));
        assertEquals("INVALID_GEOMETRY", validate(facility("RUNWAY", new BlockPos(40,65,0)), p -> true, p -> true, p -> true));
    }
    @Test void passingAboveLandingPlaneOrMovingCannotDeliver() throws Exception {
        Object pad = facility("HELIPAD", new BlockPos(0,64,0));
        var method = pad.getClass().getMethod("isParked", AABB.class, double.class);
        assertEquals(true, method.invoke(pad, new AABB(-1,64,-1,2,67,2), .1));
        assertEquals(false, method.invoke(pad, new AABB(-1,70,-1,2,73,2), .1));
        assertEquals(false, method.invoke(pad, new AABB(-1,64,-1,2,67,2), 3));
        assertEquals(false, method.invoke(pad, new AABB(-1,64,-1,2,67,2), Double.NaN));
    }
    @Test void reservationsAreExclusiveExpireAndRoundTrip() throws Exception {
        Class<?> type = Class.forName(PACKAGE + "AirportReservationBook");
        Object book = type.getConstructor().newInstance();
        UUID first = UUID.randomUUID(), second = UUID.randomUUID();
        var reserve = type.getMethod("reserve", String.class, UUID.class, long.class, long.class);
        assertEquals(true, reserve.invoke(book, "main", first, 100L, 20L));
        assertEquals(false, reserve.invoke(book, "main", second, 110L, 20L));
        CompoundTag saved = (CompoundTag) type.getMethod("save").invoke(book);
        Object restored = type.getMethod("load", CompoundTag.class).invoke(null, saved);
        assertEquals(false, reserve.invoke(restored, "main", second, 119L, 20L));
        assertEquals(true, reserve.invoke(restored, "main", second, 120L, 20L));
        assertEquals(false, reserve.invoke(restored, "other", first, 120L, 0L));
    }
}
