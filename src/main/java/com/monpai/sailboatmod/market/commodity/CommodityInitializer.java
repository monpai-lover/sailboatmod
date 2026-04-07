package com.monpai.sailboatmod.market.commodity;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

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
        Item item = findRegisteredItem(itemId);

        if (item instanceof BlockItem) {
            return CommodityCategories.BUILDING;
        }

        if (lower.contains("wheat") || lower.contains("bread") || lower.contains("carrot")
            || lower.contains("potato") || lower.contains("beetroot") || lower.contains("apple")) {
            return CommodityCategories.FOOD;
        }

        if (lower.contains("log") || lower.contains("planks") || lower.contains("wood")) {
            return CommodityCategories.WOOD;
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

        return CommodityCategories.FOOD;
    }

    private static Item findRegisteredItem(String itemId) {
        ResourceLocation key = ResourceLocation.tryParse(itemId);
        return key == null ? null : ForgeRegistries.ITEMS.getValue(key);
    }

    private CommodityInitializer() {
    }
}
