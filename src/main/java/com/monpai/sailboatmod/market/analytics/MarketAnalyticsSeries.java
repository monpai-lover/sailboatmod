package com.monpai.sailboatmod.market.analytics;

import java.util.List;

public record MarketAnalyticsSeries(
        String scopeType,
        String scopeKey,
        String displayName,
        List<MarketAnalyticsPoint> points
) {
    public MarketAnalyticsSeries {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
