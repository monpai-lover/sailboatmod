package com.monpai.sailboatmod.market.commodity;

public enum CommodityCategories {
    FOOD("food", 0, 3, 2, 0, 50),
    WOOD("wood", 0, 2, 3, 1, 80),
    ORE("ore", 1, 2, 2, 1, 100),
    METAL("metal", 1, 2, 2, 1, 120),
    GEMS("gems", 3, 1, 1, 3, 200),
    SPICES("spices", 2, 1, 1, 2, 150),
    TOOLS("tools", 1, 2, 2, 1, 100),
    LUXURY("luxury", 2, 0, 1, 3, 180);

    private final String id;
    private final int rarity;
    private final int importance;
    private final int volume;
    private final int elasticity;
    private final int baseVolatility;

    CommodityCategories(String id, int rarity, int importance, int volume, int elasticity, int baseVolatility) {
        this.id = id;
        this.rarity = rarity;
        this.importance = importance;
        this.volume = volume;
        this.elasticity = elasticity;
        this.baseVolatility = baseVolatility;
    }

    public String getId() {
        return id;
    }

    public int getRarity() {
        return rarity;
    }

    public int getImportance() {
        return importance;
    }

    public int getVolume() {
        return volume;
    }

    public int getElasticity() {
        return elasticity;
    }

    public int getBaseVolatility() {
        return baseVolatility;
    }

    public static CommodityCategories fromId(String id) {
        for (CommodityCategories category : values()) {
            if (category.id.equals(id)) {
                return category;
            }
        }
        return FOOD;
    }
}
