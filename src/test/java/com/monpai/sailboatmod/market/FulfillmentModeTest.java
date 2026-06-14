package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FulfillmentModeTest {
    @Test
    void parsesKnownValues() {
        assertEquals(FulfillmentMode.REAL_PICKUP, FulfillmentMode.fromString("REAL_PICKUP"));
        assertEquals(FulfillmentMode.AUTO_PICKUP, FulfillmentMode.fromString("AUTO_PICKUP"));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString("SELLER_SHIP"));
    }

    @Test
    void caseInsensitiveAndTrimmed() {
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(" seller_ship "));
    }

    @Test
    void unknownOrBlankDefaultsToSellerShip() {
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(null));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString(""));
        assertEquals(FulfillmentMode.SELLER_SHIP, FulfillmentMode.fromString("garbage"));
    }

    @Test
    void needsVehicleDispatchFlag() {
        assertEquals(false, FulfillmentMode.REAL_PICKUP.needsVehicleDispatch());
        assertEquals(true, FulfillmentMode.AUTO_PICKUP.needsVehicleDispatch());
        assertEquals(true, FulfillmentMode.SELLER_SHIP.needsVehicleDispatch());
    }
}
