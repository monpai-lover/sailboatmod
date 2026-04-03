package com.monpai.sailboatmod.market.commodity;

public record CommodityPriceChartPoint(
        long bucketAt,
        int averageUnitPrice,
        int minUnitPrice,
        int maxUnitPrice,
        int volume,
        int tradeCount
) {
}
