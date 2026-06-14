package com.monpai.sailboatmod.market.commodity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReferencePriceFallbackTest {
    @Test
    void prefersRecentTradeAverageWhenPresent() {
        // 成交均价 > 0 → 用成交均价（即便有挂单/basePrice）
        assertEquals(120, CommodityMarketService.resolveReferencePrice(120, 80, 18));
    }

    @Test
    void fallsBackToLowestActiveAskWhenNoTrades() {
        // 无成交（0）→ 用在售最低价
        assertEquals(80, CommodityMarketService.resolveReferencePrice(0, 80, 18));
    }

    @Test
    void fallsBackToBasePriceWhenNoTradesAndNoListings() {
        // 无成交、无挂单 → 用 basePrice（定价模型兜底）
        assertEquals(18, CommodityMarketService.resolveReferencePrice(0, 0, 18));
    }

    @Test
    void basePriceIsClampedToAtLeastOne() {
        assertEquals(1, CommodityMarketService.resolveReferencePrice(0, 0, 0));
    }

    @Test
    void negativeInputsAreTreatedAsAbsent() {
        assertEquals(50, CommodityMarketService.resolveReferencePrice(-1, 50, 18));
        assertEquals(18, CommodityMarketService.resolveReferencePrice(-1, -1, 18));
    }
}
