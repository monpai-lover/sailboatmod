package com.monpai.sailboatmod.market.analytics;

public record MarketAnalyticsPoint(
        long bucketAt,
        int value,
        int volume,
        int tradeCount
) {
}
