package com.monpai.sailboatmod.market.commodity;

public record CommodityMarketState(
        String commodityKey,
        int basePrice,
        int currentStock,
        int volatility,
        int spreadBp,
        int stockFloor,
        int stockCeil,
        int priceFloor,
        int priceCeil,
        long lastTradeAt,
        long updatedAt,
        int version
) {
    public CommodityMarketState {
        commodityKey = commodityKey == null ? "" : commodityKey.trim();
        basePrice = Math.max(0, basePrice);
        volatility = Math.max(0, Math.min(10_000, volatility));
        spreadBp = Math.max(0, Math.min(9_500, spreadBp));
        priceFloor = Math.max(0, priceFloor);
        priceCeil = Math.max(priceFloor, priceCeil);
        version = Math.max(0, version);
    }

    public CommodityMarketState withCurrentStock(int newStock) {
        return new CommodityMarketState(
                commodityKey,
                basePrice,
                newStock,
                volatility,
                spreadBp,
                stockFloor,
                stockCeil,
                priceFloor,
                priceCeil,
                lastTradeAt,
                System.currentTimeMillis(),
                version + 1
        );
    }

    public CommodityMarketState withTradeTimestamp(long timestamp) {
        return new CommodityMarketState(
                commodityKey,
                basePrice,
                currentStock,
                volatility,
                spreadBp,
                stockFloor,
                stockCeil,
                priceFloor,
                priceCeil,
                timestamp,
                timestamp,
                version + 1
        );
    }
}