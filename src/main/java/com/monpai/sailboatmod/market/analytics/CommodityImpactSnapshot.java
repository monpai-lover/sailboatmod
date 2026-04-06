package com.monpai.sailboatmod.market.analytics;

public record CommodityImpactSnapshot(
        String commodityKey,
        int referenceUnitPrice,
        int currentClosePrice,
        int liquidityScore,
        int inventoryPressureBp,
        int buyPressureBp,
        int volatilityBp
) {
}
