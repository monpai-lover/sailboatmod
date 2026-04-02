package com.monpai.sailboatmod.market.commodity;

public record CommodityTradeRecord(
        String commodityKey,
        MarketTradeSide tradeSide,
        int quantity,
        int unitPrice,
        int totalPrice,
        String sourceMarketPos,
        String sourceNationId,
        String targetNationId,
        String actorUuid,
        String actorName,
        long createdAt
) {
    public CommodityTradeRecord {
        commodityKey = sanitize(commodityKey);
        sourceMarketPos = sanitize(sourceMarketPos);
        sourceNationId = sanitize(sourceNationId);
        targetNationId = sanitize(targetNationId);
        actorUuid = sanitize(actorUuid);
        actorName = sanitize(actorName);
        quantity = Math.max(1, quantity);
        unitPrice = Math.max(0, unitPrice);
        totalPrice = Math.max(0, totalPrice);
        createdAt = Math.max(0L, createdAt);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}