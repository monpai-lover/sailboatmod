package com.monpai.sailboatmod.market.logistics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShippingTraceServiceTest {
    @Test
    void activeFilterHidesTerminalStatuses() {
        assertTrue(ShippingTraceService.isMapVisibleStatus("SAILING"));
        assertTrue(ShippingTraceService.isMapVisibleStatus("IN_TRANSIT"));
        assertTrue(ShippingTraceService.isMapVisibleStatus("ARRIVED"));
        assertFalse(ShippingTraceService.isMapVisibleStatus("DELIVERED"));
        assertFalse(ShippingTraceService.isMapVisibleStatus("CLAIMED"));
        assertFalse(ShippingTraceService.isMapVisibleStatus("FAILED"));
        assertFalse(ShippingTraceService.isMapVisibleStatus("FAILED_ROLLBACK"));
        assertFalse(ShippingTraceService.isMapVisibleStatus("CANCELLED"));
    }
}
