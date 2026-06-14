package com.monpai.sailboatmod.market.commodity;

public final class CommodityInitializer {

    public static CommodityDefinition createDefault(String commodityKey, String itemId, String displayName) {
        CommodityCategories category = detectCategory(itemId);
        return new CommodityDefinition(
            commodityKey,
            itemId,
            "",
            displayName,
            1,
            category.getId(),
            true,
            category.getRarity(),
            category.getImportance(),
            category.getVolume(),
            category.getElasticity(),
            category.getBaseVolatility()
        );
    }

    private static CommodityCategories detectCategory(String itemId) {
        String lower = itemId.toLowerCase();

        if (containsAny(lower,
                "musket", "bayonet", "pistol", "blunderbuss", "handgonne", "matchlock",
                "gun", "raygun", "pulsargun", "cannon", "bombard", "culverin", "ribauldequin",
                "bullet", "cartridge", "ammo", "arrow", "bow", "crossbow",
                "longsword", "claymore", "katana", "spear", "pike", "lance", "halberd",
                "glaive", "warglaive", "rapier", "cutlass", "scythe", "dagger", "mace",
                "greathammer", "warhammer", "chakram", "twinblade", "staff", "wand", "tome")) {
            return CommodityCategories.WEAPON;
        }

        if (containsAny(lower,
                "helmet", "chestplate", "leggings", "boots", "armor", "shield", "targe",
                "pauldrons", "mask", "cloak", "hood", "tunic", "pants")) {
            return CommodityCategories.ARMOR;
        }

        if (containsAny(lower,
                "boat", "ship", "sail", "carriage", "route_book", "road_planner",
                "post_station", "dock", "waystone", "compass", "map", "tool", "hammer")) {
            return CommodityCategories.UTILITY;
        }

        if (containsAny(lower,
                "wheat", "bread", "carrot", "potato", "beetroot", "apple", "berry", "berries",
                "soup", "stew", "pie", "cake", "cookie", "candy", "chocolate", "cheese",
                "rice", "corn", "dough", "meat", "beef", "pork", "chop", "mutton",
                "chicken", "rabbit", "fish", "salmon", "cod", "sushi", "meal", "food")) {
            return CommodityCategories.FOOD;
        }

        if (lower.contains("log") || lower.contains("planks") || lower.contains("wood")) {
            return CommodityCategories.WOOD;
        }

        if (containsAny(lower,
                "slab", "stairs", "wall", "brick", "bricks", "block", "tile", "tiles",
                "pillar", "planks", "shingles", "frame", "glass", "pane", "stone",
                "cobble", "basalt", "limestone", "sandstone", "terracotta")) {
            return CommodityCategories.BUILDING;
        }

        if (lower.contains("diamond") || lower.contains("emerald")) {
            return CommodityCategories.GEMS;
        }

        if (lower.contains("_ore") || lower.contains("coal") || lower.contains("raw_")) {
            return CommodityCategories.ORE;
        }

        if (lower.contains("ingot") || lower.contains("nugget")) {
            return CommodityCategories.METAL;
        }

        if (lower.contains("cocoa") || lower.contains("sugar")) {
            return CommodityCategories.SPICES;
        }

        if (lower.contains("pickaxe") || lower.contains("axe") || lower.contains("sword")
            || lower.contains("shovel") || lower.contains("hoe")) {
            return CommodityCategories.TOOLS;
        }

        if (lower.contains("golden_apple") || lower.contains("enchanted")) {
            return CommodityCategories.LUXURY;
        }

        if (containsAny(lower, "stick", "string", "leather", "feather", "bone", "plate", "shard", "scrap")) {
            return CommodityCategories.MATERIAL;
        }

        return CommodityCategories.OTHER;
    }

    private static boolean containsAny(String value, String... keywords) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (String keyword : keywords) {
            if (keyword != null && !keyword.isBlank() && value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private CommodityInitializer() {
    }
}
