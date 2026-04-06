package com.monpai.sailboatmod.market.analytics;

public record CommodityTradeTick(
        long createdAt,
        int unitPrice,
        int quantity
) {
}
