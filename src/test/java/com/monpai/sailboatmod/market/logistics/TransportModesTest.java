package com.monpai.sailboatmod.market.logistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransportModesTest {
    @Test
    void waterModesAreSailboats() {
        for (String mode : new String[]{"PORT", "port", " Water ", "SEA", "SAIL", "BOAT", "SHIP"}) {
            assertEquals(TransportModes.KIND_SAILBOAT, TransportModes.vehicleKind(mode), mode);
        }
    }

    @Test
    void landModesAreCarriages() {
        for (String mode : new String[]{"LAND", "POST_STATION", "ROAD", "CARRIAGE"}) {
            assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind(mode), mode);
        }
    }

    @Test
    void railModesAreTrains() {
        for (String mode : new String[]{TransportModes.RAIL, "rail", "TRAIN", "CREATE_TRAIN"}) {
            assertEquals(TransportModes.KIND_TRAIN, TransportModes.vehicleKind(mode), mode);
        }
    }

    @Test
    void modesAreMatchedByWholeWordsNotByFragments() {
        assertEquals(TransportModes.KIND_TRAIN, TransportModes.vehicleKind("create-rail"));
        assertEquals(TransportModes.KIND_SAILBOAT, TransportModes.vehicleKind("RIVER_BOAT"));
        // a custom name that merely contains a water word is not a boat
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind("BOATYARD_EXPRESS"));
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind("SPORTS_CARGO"));
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind("TRAINING_ROUTE"));
    }

    @Test
    void unknownModesKeepTheOldCarriageFallback() {
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind(""));
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind(null));
        assertEquals(TransportModes.KIND_CARRIAGE, TransportModes.vehicleKind("HOVERCRAFT"));
    }

    @Test
    void defaultVehicleNamesFollowTheKind() {
        assertEquals("帆船", TransportModes.defaultVehicleName("PORT"));
        assertEquals("马车", TransportModes.defaultVehicleName("LAND"));
        assertEquals("列车", TransportModes.defaultVehicleName("RAIL"));
    }
}
