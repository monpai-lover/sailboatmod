package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketReferencePriceWindowTest {
    @Test
    void priceWithinFiftyPercentBandIsAccepted() {
        // 参考价 100 → 允许区间 [50, 150]
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 120);
        assertTrue(w.valid());
        assertEquals(50, w.minAllowedUnitPrice());
        assertEquals(150, w.maxAllowedUnitPrice());
        assertEquals(120, w.requestedUnitPrice());
        assertEquals(100, w.referenceUnitPrice());
    }

    @Test
    void priceAboveCeilIsRejected() {
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 151);
        assertFalse(w.valid());
    }

    @Test
    void priceBelowFloorIsRejected() {
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(100, 49);
        assertFalse(w.valid());
    }

    @Test
    void boundariesAreInclusive() {
        assertTrue(MarketPricePolicy.referencePriceWindow(100, 50).valid());
        assertTrue(MarketPricePolicy.referencePriceWindow(100, 150).valid());
    }

    @Test
    void nonPositiveReferenceFallsBackToMinimumOne() {
        // 参考价 <=0 时按 1 处理，避免区间塌缩为 [0,0]
        MarketPricePolicy.ListingPriceWindow w = MarketPricePolicy.referencePriceWindow(0, 1);
        assertEquals(1, w.referenceUnitPrice());
        assertTrue(w.minAllowedUnitPrice() >= 1);
        assertTrue(w.valid());
    }
}
