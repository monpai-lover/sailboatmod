package com.monpai.sailboatmod.market.commodity;

public record CommodityQuote(
        String commodityKey,
        int quantity,
        int buyPrice,
        int sellPrice,
        int buyUnitPrice,
        int sellUnitPrice,
        int projectedBuyStock,
        int projectedSellStock
) {
}