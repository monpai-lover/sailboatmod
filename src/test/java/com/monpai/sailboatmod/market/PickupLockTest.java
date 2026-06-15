package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupLockTest {
    @Test
    void loadAmountIsMinOfVehicleSpaceAndLockedQuantity() {
        assertEquals(20, PickupLock.loadableAmount(20, 64));
        assertEquals(64, PickupLock.loadableAmount(200, 64));
        assertEquals(0, PickupLock.loadableAmount(0, 64));
        assertEquals(0, PickupLock.loadableAmount(20, 0));
    }

    @Test
    void negativeInputsClampToZero() {
        assertEquals(0, PickupLock.loadableAmount(-5, 64));
        assertEquals(0, PickupLock.loadableAmount(20, -1));
    }

    @Test
    void isPickupOrderRecognizesLockedPickupStatus() {
        assertTrue(PickupLock.isPickupOrder("PICKUP_LOCKED", "REAL_PICKUP"));
        assertTrue(PickupLock.isPickupOrder("PICKUP_LOCKED", "AUTO_PICKUP"));
        assertFalse(PickupLock.isPickupOrder("WAITING_SHIPMENT", "SELLER_SHIP"));
        assertFalse(PickupLock.isPickupOrder("IN_TRANSIT", "REAL_PICKUP"));
    }

    @Test
    void ownsVehicleMatchesBuyerUuid() {
        assertTrue(PickupLock.vehicleBelongsToBuyer("uuid-a", "uuid-a"));
        assertFalse(PickupLock.vehicleBelongsToBuyer("uuid-a", "uuid-b"));
        assertFalse(PickupLock.vehicleBelongsToBuyer("uuid-a", null));
        assertFalse(PickupLock.vehicleBelongsToBuyer(null, "uuid-a"));
    }
}
