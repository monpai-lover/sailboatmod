package com.monpai.sailboatmod.market.commodity;

public record CommodityDefinition(
        String commodityKey,
        String itemId,
        String variantKey,
        String displayName,
        int unitSize,
        String category,
        boolean tradeEnabled,
        int rarity,
        int importance,
        int volume,
        int elasticity,
        int baseVolatility
) {
    public CommodityDefinition {
        commodityKey = sanitize(commodityKey);
        itemId = sanitize(itemId);
        variantKey = sanitize(variantKey);
        displayName = sanitize(displayName);
        unitSize = Math.max(1, unitSize);
        category = sanitize(category);
        rarity = Math.max(0, Math.min(3, rarity));
        importance = Math.max(0, Math.min(3, importance));
        volume = Math.max(1, Math.min(4, volume));
        elasticity = Math.max(0, Math.min(3, elasticity));
        baseVolatility = Math.max(0, Math.min(10000, baseVolatility));
    }

    private static String sanitize(String value) {
        return value == null ? "" : value.trim();
    }
}