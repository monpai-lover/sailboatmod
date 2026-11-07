package com.monpai.sailboatmod.transport.rail;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RailRouteStopoverTest {
    private static final RailStationRef HOME = station(1, 10);
    private static final RailStationRef FINAL = station(2, 50);
    private static final RailStationRef FIRST = station(3, 30);
    private static final RailStationRef SECOND = station(4, 20);

    @Test
    void stopoversKeepTheirAuthoredOrderBeforeTheDeliveryStation() {
        var input = new ArrayList<>(List.of(FIRST, SECOND));
        var route = new RailRoute(HOME, FINAL, HOME.stationPos(), FINAL.stationPos(), BlockPos.ZERO, 60, "Freight", input);
        input.clear();

        assertEquals(List.of(FIRST, SECOND), route.stopovers());
        assertEquals(List.of(1, 1), route.stopoverSeconds());
        assertEquals(List.of(FIRST, SECOND, FINAL), route.outboundStations());
        assertThrows(UnsupportedOperationException.class, () -> route.stopovers().clear());
    }

    @Test
    void rejectsRepeatedOrForeignStationIdentities() {
        assertTrue(RailRoute.validStopovers(HOME, FINAL, List.of()));
        assertFalse(RailRoute.validStopovers(HOME, FINAL, List.of(FINAL)));
        assertFalse(RailRoute.validStopovers(HOME, FINAL, List.of(FIRST, station(3, 40))));
        assertFalse(RailRoute.validStopovers(HOME, FINAL, List.of(FIRST, station(5, 30))));
        assertFalse(RailRoute.validStopovers(HOME, FINAL, List.of(new RailStationRef(
                "minecraft:the_nether", SECOND.stationPos(), SECOND.stationId()))));
    }

    @Test
    void dwellSecondsFollowTheirStationOrderAndMustBeInBounds() {
        var input = new ArrayList<>(List.of(45, 3600));
        var stops = List.of(FIRST, SECOND);
        var route = new RailRoute(HOME, FINAL, HOME.stationPos(), FINAL.stationPos(), BlockPos.ZERO,
                60, "Freight", stops, input);
        input.set(0, 1);

        assertEquals(List.of(45, 3600), route.stopoverSeconds());
        assertThrows(UnsupportedOperationException.class, () -> route.stopoverSeconds().clear());
        assertFalse(RailRoute.validStopoverSeconds(stops, List.of(45)));
        assertFalse(RailRoute.validStopoverSeconds(stops, List.of(0, 45)));
        assertFalse(RailRoute.validStopoverSeconds(stops, List.of(45, 3601)));
    }

    private static RailStationRef station(int id, int x) {
        return new RailStationRef("minecraft:overworld", new BlockPos(x, 64, 0), new UUID(0, id));
    }
}
