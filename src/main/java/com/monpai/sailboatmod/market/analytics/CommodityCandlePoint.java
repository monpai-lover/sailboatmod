package com.monpai.sailboatmod.market.analytics;

public record CommodityCandlePoint(
        long bucketAt,
        int openUnitPrice,
        int highUnitPrice,
        int lowUnitPrice,
        int closeUnitPrice,
        int volume,
        int tradeCount
) {
}
