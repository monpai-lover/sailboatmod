package com.monpai.sailboatmod.market.commodity;

public record BuyOrder(
        String orderId,
        String buyerUuid,
        String buyerName,
        String commodityKey,
        int quantity,
        int minPriceBp,
        int maxPriceBp,
        long createdAt,
        String status
) {
    public BuyOrder {
        orderId = orderId == null ? "" : orderId;
        buyerUuid = buyerUuid == null ? "" : buyerUuid;
        buyerName = buyerName == null ? "" : buyerName;
        commodityKey = commodityKey == null ? "" : commodityKey;
        quantity = Math.max(1, quantity);
        minPriceBp = Math.max(-5000, Math.min(5000, minPriceBp));
        maxPriceBp = Math.max(-5000, Math.min(5000, maxPriceBp));
        status = status == null ? "ACTIVE" : status;
    }

    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
