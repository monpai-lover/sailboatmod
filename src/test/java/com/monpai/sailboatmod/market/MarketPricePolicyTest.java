package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketPricePolicyTest {
    @Test
    void unmodeledModItemsAllowAnyPositiveManualPrice() {
        MarketPricePolicy.ListingPriceWindow window = MarketPricePolicy.listingWindow(
                false,
                18,
                180,
                -1000,
                1000
        );

        assertFalse(window.constrained());
        assertTrue(window.valid());
        assertEquals(1, window.minAllowedUnitPrice());
        assertEquals(Integer.MAX_VALUE, window.maxAllowedUnitPrice());
        assertEquals(180, window.requestedUnitPrice());
    }

    @Test
    void modeledItemsStillRejectPricesOutsideConfiguredBand() {
        MarketPricePolicy.ListingPriceWindow window = MarketPricePolicy.listingWindow(
                true,
                18,
                180,
                -1000,
                1000
        );

        assertTrue(window.constrained());
        assertFalse(window.valid());
    }
}
