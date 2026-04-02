package com.monpai.sailboatmod.market.commodity;

public record CommodityDefinition(
        String commodityKey,
        String itemId,
        String variantKey,
        String displayName,
        int unitSize,
        String category,
        boolean tradeEnabled
) {
    public CommodityDefinition {
        commodityKey = sanitize(commodityKey);
        itemId = sanitize(itemId);
        variantKey = sanitize(variantKey);
        displayName = sanitize(displayName);
        unitSize = Math.max(1, unitSize);
        category = sanitize(category);
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}