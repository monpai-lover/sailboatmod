package com.monpai.sailboatmod.market;

import com.monpai.sailboatmod.market.logistics.TransportDispatchService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DispatchEligibilityTest {
    @Test
    void sellerShipWaitingOrderIsBackgroundDispatchable() {
        assertTrue(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "SELLER_SHIP"));
    }

    @Test
    void autoPickupWaitingOrderIsBackgroundDispatchable() {
        assertTrue(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "AUTO_PICKUP"));
    }

    @Test
    void realPickupIsNotBackgroundDispatchable() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", "REAL_PICKUP"));
    }

    @Test
    void nonWaitingStatusIsNotDispatchable() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable("IN_TRANSIT", "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("CLAIMED", "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("PAID", "SELLER_SHIP"));
    }

    @Test
    void blankOrNullSafe() {
        assertFalse(TransportDispatchService.isBackgroundDispatchable(null, "SELLER_SHIP"));
        assertFalse(TransportDispatchService.isBackgroundDispatchable("WAITING_SHIPMENT", null));
    }
}
