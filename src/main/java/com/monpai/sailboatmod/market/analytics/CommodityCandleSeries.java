package com.monpai.sailboatmod.market.analytics;

import java.util.List;

public record CommodityCandleSeries(
        String commodityKey,
        String displayName,
        String timeframe,
        List<CommodityCandlePoint> points
) {
    public CommodityCandleSeries {
        points = points == null ? List.of() : List.copyOf(points);
    }
}
