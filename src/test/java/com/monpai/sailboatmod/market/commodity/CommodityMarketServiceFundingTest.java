package com.monpai.sailboatmod.market.commodity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommodityMarketServiceFundingTest {
    @Test
    void buyOrderReserveUsesMaxBidPriceAndQuantity() {
        assertEquals(138L, CommodityMarketService.reservedBalanceForBuyOrder(18, 6, 2800));
        assertEquals(90L, CommodityMarketService.reservedBalanceForBuyOrder(18, 5, 0));
        assertEquals(63L, CommodityMarketService.reservedBalanceForBuyOrder(18, 5, -3000));
    }
}
