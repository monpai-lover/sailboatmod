package com.monpai.sailboatmod.market.commodity;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public final class CommodityConfigLoader {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static final Map<String, Override> OVERRIDES = new HashMap<>();

    public record Override(int rarity, int importance, int elasticity, int baseVolatility, int basePrice, String category) {}

    public static void load() {
        OVERRIDES.clear();
        Path configDir = FMLPaths.CONFIGDIR.get().resolve("sailboatmod");
        Path file = configDir.resolve("commodities.json");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(configDir);
                Files.writeString(file, defaultJson());
                LOGGER.info("[Market] Generated default commodities.json at {}", file);
            }
            String json = Files.readString(file);
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            JsonArray overrides = root.getAsJsonArray("overrides");
            for (JsonElement el : overrides) {
                JsonObject o = el.getAsJsonObject();
                String itemId = o.get("itemId").getAsString();
                int rarity = o.has("rarity") ? o.get("rarity").getAsInt() : -1;
                int importance = o.has("importance") ? o.get("importance").getAsInt() : -1;
                int elasticity = o.has("elasticity") ? o.get("elasticity").getAsInt() : -1;
                int baseVolatility = o.has("baseVolatility") ? o.get("baseVolatility").getAsInt() : -1;
                int basePrice = o.has("basePrice") ? o.get("basePrice").getAsInt() : -1;
                String category = o.has("category") ? o.get("category").getAsString() : null;
                OVERRIDES.put(itemId, new Override(rarity, importance, elasticity, baseVolatility, basePrice, category));
            }
            LOGGER.info("[Market] Loaded {} commodity overrides from commodities.json", OVERRIDES.size());
        } catch (IOException e) {
            LOGGER.error("[Market] Failed to load commodities.json", e);
        }
    }

    /** Apply JSON overrides on top of a base definition. Returns base if no override exists. */
    public static CommodityDefinition apply(CommodityDefinition base) {
        Override ov = OVERRIDES.get(base.itemId());
        if (ov == null) return base;
        return new CommodityDefinition(
                base.commodityKey(),
                base.itemId(),
                base.variantKey(),
                base.displayName(),
                base.unitSize(),
                ov.category() != null ? ov.category() : base.category(),
                base.tradeEnabled(),
                ov.rarity() >= 0 ? ov.rarity() : base.rarity(),
                ov.importance() >= 0 ? ov.importance() : base.importance(),
                base.volume(),
                ov.elasticity() >= 0 ? ov.elasticity() : base.elasticity(),
                ov.baseVolatility() >= 0 ? ov.baseVolatility() : base.baseVolatility()
        );
    }

    public static int getBasePrice(String itemId, int fallback) {
        Override ov = OVERRIDES.get(itemId);
        return (ov != null && ov.basePrice() >= 0) ? ov.basePrice() : fallback;
    }

    private static String defaultJson() {
        return """
{
  "overrides": [
    { "itemId": "minecraft:diamond", "rarity": 3, "category": "gems", "basePrice": 64 },
    { "itemId": "minecraft:emerald", "rarity": 3, "category": "gems", "basePrice": 48 },
    { "itemId": "minecraft:netherite_ingot", "rarity": 3, "category": "metal", "basePrice": 72 },
    { "itemId": "minecraft:netherite_scrap", "rarity": 2, "category": "metal", "basePrice": 40 },
    { "itemId": "minecraft:ancient_debris", "rarity": 3, "category": "ore", "basePrice": 56 },
    { "itemId": "minecraft:diamond_ore", "rarity": 2, "category": "ore", "basePrice": 40 },
    { "itemId": "minecraft:deepslate_diamond_ore", "rarity": 2, "category": "ore", "basePrice": 44 },
    { "itemId": "minecraft:emerald_ore", "rarity": 2, "category": "ore", "basePrice": 32 },
    { "itemId": "minecraft:redstone", "rarity": 1, "category": "ore", "basePrice": 10 },
    { "itemId": "minecraft:lapis_lazuli", "rarity": 1, "category": "gems", "basePrice": 12 },
    { "itemId": "minecraft:quartz", "rarity": 1, "category": "gems", "basePrice": 10 },
    { "itemId": "minecraft:amethyst_shard", "rarity": 1, "category": "gems", "basePrice": 14 },
    { "itemId": "minecraft:coal", "rarity": 0, "category": "ore", "basePrice": 4 },
    { "itemId": "minecraft:charcoal", "rarity": 0, "category": "ore", "basePrice": 4 },
    { "itemId": "minecraft:copper_ingot", "rarity": 0, "category": "metal", "basePrice": 6 },
    { "itemId": "minecraft:gold_ingot", "rarity": 1, "category": "metal", "basePrice": 18 },
    { "itemId": "minecraft:iron_ingot", "rarity": 1, "category": "metal", "basePrice": 12 },
    { "itemId": "minecraft:iron_nugget", "rarity": 0, "category": "metal", "basePrice": 1 },
    { "itemId": "minecraft:gold_nugget", "rarity": 0, "category": "metal", "basePrice": 2 },
    { "itemId": "minecraft:raw_iron", "rarity": 0, "category": "ore", "basePrice": 8 },
    { "itemId": "minecraft:raw_gold", "rarity": 1, "category": "ore", "basePrice": 12 },
    { "itemId": "minecraft:raw_copper", "rarity": 0, "category": "ore", "basePrice": 4 },
    { "itemId": "minecraft:iron_ore", "rarity": 0, "category": "ore", "basePrice": 8 },
    { "itemId": "minecraft:gold_ore", "rarity": 1, "category": "ore", "basePrice": 14 },
    { "itemId": "minecraft:copper_ore", "rarity": 0, "category": "ore", "basePrice": 4 },
    { "itemId": "minecraft:deepslate_iron_ore", "rarity": 1, "category": "ore", "basePrice": 9 },
    { "itemId": "minecraft:deepslate_gold_ore", "rarity": 1, "category": "ore", "basePrice": 15 },
    { "itemId": "minecraft:deepslate_copper_ore", "rarity": 0, "category": "ore", "basePrice": 5 },
    { "itemId": "minecraft:lapis_ore", "rarity": 1, "category": "ore", "basePrice": 10 },
    { "itemId": "minecraft:redstone_ore", "rarity": 1, "category": "ore", "basePrice": 10 },
    { "itemId": "minecraft:deepslate_redstone_ore", "rarity": 1, "category": "ore", "basePrice": 11 },
    { "itemId": "minecraft:nether_gold_ore", "rarity": 1, "category": "nether", "basePrice": 10 },
    { "itemId": "minecraft:nether_quartz_ore", "rarity": 1, "category": "nether", "basePrice": 9 },
    { "itemId": "minecraft:oak_log", "rarity": 0, "category": "wood", "basePrice": 3 },
    { "itemId": "minecraft:spruce_log", "rarity": 0, "category": "wood", "basePrice": 3 },
    { "itemId": "minecraft:birch_log", "rarity": 0, "category": "wood", "basePrice": 3 },
    { "itemId": "minecraft:jungle_log", "rarity": 0, "category": "wood", "basePrice": 4 },
    { "itemId": "minecraft:acacia_log", "rarity": 0, "category": "wood", "basePrice": 3 },
    { "itemId": "minecraft:dark_oak_log", "rarity": 0, "category": "wood", "basePrice": 4 },
    { "itemId": "minecraft:mangrove_log", "rarity": 1, "category": "wood", "basePrice": 5 },
    { "itemId": "minecraft:cherry_log", "rarity": 1, "category": "wood", "basePrice": 5 },
    { "itemId": "minecraft:oak_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:spruce_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:birch_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:jungle_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:acacia_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:dark_oak_planks", "rarity": 0, "category": "wood", "basePrice": 1 },
    { "itemId": "minecraft:mangrove_planks", "rarity": 0, "category": "wood", "basePrice": 2 },
    { "itemId": "minecraft:cherry_planks", "rarity": 0, "category": "wood", "basePrice": 2 },
    { "itemId": "minecraft:wheat", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:bread", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:carrot", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:potato", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:baked_potato", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:beetroot", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:pumpkin", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:melon_slice", "rarity": 0, "category": "food", "basePrice": 1 },
    { "itemId": "minecraft:apple", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:golden_apple", "rarity": 2, "category": "food", "basePrice": 36 },
    { "itemId": "minecraft:enchanted_golden_apple", "rarity": 3, "category": "food", "basePrice": 120 },
    { "itemId": "minecraft:sugar_cane", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:sugar", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:egg", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:milk_bucket", "rarity": 1, "category": "food", "basePrice": 10 },
    { "itemId": "minecraft:honey_bottle", "rarity": 1, "category": "food", "basePrice": 8 },
    { "itemId": "minecraft:glow_berries", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:sweet_berries", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:cookie", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:cake", "rarity": 1, "category": "food", "basePrice": 18 },
    { "itemId": "minecraft:pumpkin_pie", "rarity": 1, "category": "food", "basePrice": 10 },
    { "itemId": "minecraft:rabbit_stew", "rarity": 1, "category": "food", "basePrice": 14 },
    { "itemId": "minecraft:mushroom_stew", "rarity": 0, "category": "food", "basePrice": 6 },
    { "itemId": "minecraft:beetroot_soup", "rarity": 0, "category": "food", "basePrice": 6 },
    { "itemId": "minecraft:dried_kelp", "rarity": 0, "category": "food", "basePrice": 2 },
    { "itemId": "minecraft:golden_carrot", "rarity": 1, "category": "food", "basePrice": 14 },
    { "itemId": "minecraft:beef", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:cooked_beef", "rarity": 0, "category": "food", "basePrice": 7 },
    { "itemId": "minecraft:porkchop", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:cooked_porkchop", "rarity": 0, "category": "food", "basePrice": 7 },
    { "itemId": "minecraft:chicken", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:cooked_chicken", "rarity": 0, "category": "food", "basePrice": 6 },
    { "itemId": "minecraft:mutton", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:cooked_mutton", "rarity": 0, "category": "food", "basePrice": 7 },
    { "itemId": "minecraft:rabbit", "rarity": 1, "category": "food", "basePrice": 5 },
    { "itemId": "minecraft:cooked_rabbit", "rarity": 1, "category": "food", "basePrice": 8 },
    { "itemId": "minecraft:cod", "rarity": 0, "category": "food", "basePrice": 3 },
    { "itemId": "minecraft:cooked_cod", "rarity": 0, "category": "food", "basePrice": 5 },
    { "itemId": "minecraft:salmon", "rarity": 0, "category": "food", "basePrice": 4 },
    { "itemId": "minecraft:cooked_salmon", "rarity": 0, "category": "food", "basePrice": 6 },
    { "itemId": "minecraft:pufferfish", "rarity": 1, "category": "food", "basePrice": 5 },
    { "itemId": "minecraft:tropical_fish", "rarity": 1, "category": "food", "basePrice": 5 },
    { "itemId": "minecraft:bamboo", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:cactus", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:kelp", "rarity": 0, "category": "plant", "basePrice": 1 },
    { "itemId": "minecraft:vine", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:lily_pad", "rarity": 0, "category": "plant", "basePrice": 3 },
    { "itemId": "minecraft:sea_pickle", "rarity": 1, "category": "plant", "basePrice": 5 },
    { "itemId": "minecraft:stick", "rarity": 0, "category": "material", "basePrice": 1 },
    { "itemId": "minecraft:oak_sapling", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:spruce_sapling", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:birch_sapling", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:jungle_sapling", "rarity": 0, "category": "plant", "basePrice": 3 },
    { "itemId": "minecraft:acacia_sapling", "rarity": 0, "category": "plant", "basePrice": 2 },
    { "itemId": "minecraft:dark_oak_sapling", "rarity": 0, "category": "plant", "basePrice": 3 },
    { "itemId": "minecraft:mangrove_propagule", "rarity": 1, "category": "plant", "basePrice": 4 },
    { "itemId": "minecraft:cherry_sapling", "rarity": 1, "category": "plant", "basePrice": 4 },
    { "itemId": "minecraft:pumpkin_seeds", "rarity": 0, "category": "crop", "basePrice": 1 },
    { "itemId": "minecraft:melon_seeds", "rarity": 0, "category": "crop", "basePrice": 1 },
    { "itemId": "minecraft:beetroot_seeds", "rarity": 0, "category": "crop", "basePrice": 1 },
    { "itemId": "minecraft:wheat_seeds", "rarity": 0, "category": "crop", "basePrice": 1 },
    { "itemId": "minecraft:nether_wart", "rarity": 1, "category": "crop", "basePrice": 6 },
    { "itemId": "minecraft:leather", "rarity": 0, "category": "material", "basePrice": 5 },
    { "itemId": "minecraft:rabbit_hide", "rarity": 0, "category": "material", "basePrice": 3 },
    { "itemId": "minecraft:string", "rarity": 0, "category": "material", "basePrice": 3 },
    { "itemId": "minecraft:feather", "rarity": 0, "category": "material", "basePrice": 2 },
    { "itemId": "minecraft:flint", "rarity": 0, "category": "material", "basePrice": 2 },
    { "itemId": "minecraft:clay_ball", "rarity": 0, "category": "material", "basePrice": 2 },
    { "itemId": "minecraft:brick", "rarity": 0, "category": "material", "basePrice": 3 },
    { "itemId": "minecraft:paper", "rarity": 0, "category": "material", "basePrice": 2 },
    { "itemId": "minecraft:book", "rarity": 1, "category": "material", "basePrice": 8 },
    { "itemId": "minecraft:gunpowder", "rarity": 1, "category": "mob_drop", "basePrice": 8 },
    { "itemId": "minecraft:bone", "rarity": 0, "category": "mob_drop", "basePrice": 3 },
    { "itemId": "minecraft:rotten_flesh", "rarity": 0, "category": "mob_drop", "basePrice": 1 },
    { "itemId": "minecraft:spider_eye", "rarity": 0, "category": "mob_drop", "basePrice": 4 },
    { "itemId": "minecraft:slime_ball", "rarity": 1, "category": "mob_drop", "basePrice": 8 },
    { "itemId": "minecraft:ender_pearl", "rarity": 2, "category": "mob_drop", "basePrice": 18 },
    { "itemId": "minecraft:blaze_rod", "rarity": 2, "category": "mob_drop", "basePrice": 20 },
    { "itemId": "minecraft:shulker_shell", "rarity": 3, "category": "mob_drop", "basePrice": 40 },
    { "itemId": "minecraft:ink_sac", "rarity": 0, "category": "mob_drop", "basePrice": 3 },
    { "itemId": "minecraft:glow_ink_sac", "rarity": 1, "category": "mob_drop", "basePrice": 7 },
    { "itemId": "minecraft:prismarine_shard", "rarity": 1, "category": "mob_drop", "basePrice": 8 },
    { "itemId": "minecraft:prismarine_crystals", "rarity": 1, "category": "mob_drop", "basePrice": 10 },
    { "itemId": "minecraft:nautilus_shell", "rarity": 2, "category": "mob_drop", "basePrice": 20 },
    { "itemId": "minecraft:scute", "rarity": 1, "category": "mob_drop", "basePrice": 12 },
    { "itemId": "minecraft:turtle_egg", "rarity": 1, "category": "mob_drop", "basePrice": 16 },
    { "itemId": "minecraft:glass_bottle", "rarity": 0, "category": "alchemy", "basePrice": 2 },
    { "itemId": "minecraft:blaze_powder", "rarity": 2, "category": "alchemy", "basePrice": 10 },
    { "itemId": "minecraft:fermented_spider_eye", "rarity": 1, "category": "alchemy", "basePrice": 8 },
    { "itemId": "minecraft:ghast_tear", "rarity": 2, "category": "alchemy", "basePrice": 24 },
    { "itemId": "minecraft:rabbit_foot", "rarity": 1, "category": "alchemy", "basePrice": 12 },
    { "itemId": "minecraft:magma_cream", "rarity": 1, "category": "alchemy", "basePrice": 10 },
    { "itemId": "minecraft:dragon_breath", "rarity": 2, "category": "alchemy", "basePrice": 18 },
    { "itemId": "minecraft:phantom_membrane", "rarity": 1, "category": "alchemy", "basePrice": 12 },
    { "itemId": "minecraft:experience_bottle", "rarity": 2, "category": "alchemy", "basePrice": 18 },
    { "itemId": "minecraft:obsidian", "rarity": 1, "category": "building", "basePrice": 8 },
    { "itemId": "minecraft:glass", "rarity": 0, "category": "building", "basePrice": 2 },
    { "itemId": "minecraft:stone", "rarity": 0, "category": "building", "basePrice": 1 },
    { "itemId": "minecraft:cobblestone", "rarity": 0, "category": "building", "basePrice": 1 },
    { "itemId": "minecraft:sand", "rarity": 0, "category": "building", "basePrice": 1 },
    { "itemId": "minecraft:gravel", "rarity": 0, "category": "building", "basePrice": 1 },
    { "itemId": "minecraft:chain", "rarity": 0, "category": "building", "basePrice": 4 },
    { "itemId": "minecraft:iron_bars", "rarity": 0, "category": "building", "basePrice": 5 },
    { "itemId": "minecraft:terracotta", "rarity": 0, "category": "building", "basePrice": 3 },
    { "itemId": "minecraft:white_wool", "rarity": 0, "category": "building", "basePrice": 4 },
    { "itemId": "minecraft:glass_pane", "rarity": 0, "category": "building", "basePrice": 1 },
    { "itemId": "minecraft:quartz_block", "rarity": 1, "category": "building", "basePrice": 18 },
    { "itemId": "minecraft:sea_lantern", "rarity": 1, "category": "building", "basePrice": 20 },
    { "itemId": "minecraft:prismarine", "rarity": 1, "category": "building", "basePrice": 10 },
    { "itemId": "minecraft:dark_prismarine", "rarity": 1, "category": "building", "basePrice": 14 },
    { "itemId": "minecraft:glowstone_dust", "rarity": 1, "category": "nether", "basePrice": 6 },
    { "itemId": "minecraft:glowstone", "rarity": 1, "category": "nether", "basePrice": 16 },
    { "itemId": "minecraft:netherrack", "rarity": 0, "category": "nether", "basePrice": 1 },
    { "itemId": "minecraft:soul_sand", "rarity": 1, "category": "nether", "basePrice": 5 },
    { "itemId": "minecraft:nether_brick", "rarity": 1, "category": "nether", "basePrice": 4 },
    { "itemId": "minecraft:end_stone", "rarity": 1, "category": "end", "basePrice": 6 },
    { "itemId": "minecraft:chorus_fruit", "rarity": 1, "category": "end", "basePrice": 8 },
    { "itemId": "minecraft:popped_chorus_fruit", "rarity": 1, "category": "end", "basePrice": 10 },
    { "itemId": "minecraft:ender_eye", "rarity": 2, "category": "end", "basePrice": 28 },
    { "itemId": "minecraft:elytra", "rarity": 3, "category": "end", "basePrice": 160 },
    { "itemId": "minecraft:heart_of_the_sea", "rarity": 3, "category": "treasure", "basePrice": 80 },
    { "itemId": "minecraft:totem_of_undying", "rarity": 3, "category": "treasure", "basePrice": 96 },
    { "itemId": "minecraft:echo_shard", "rarity": 3, "category": "treasure", "basePrice": 48 },
    { "itemId": "minecraft:enchanted_book", "rarity": 2, "category": "treasure", "basePrice": 32 },
    { "itemId": "minecraft:name_tag", "rarity": 2, "category": "treasure", "basePrice": 24 },
    { "itemId": "minecraft:saddle", "rarity": 2, "category": "treasure", "basePrice": 20 },
    { "itemId": "minecraft:redstone_torch", "rarity": 0, "category": "redstone", "basePrice": 5 },
    { "itemId": "minecraft:repeater", "rarity": 1, "category": "redstone", "basePrice": 10 },
    { "itemId": "minecraft:comparator", "rarity": 1, "category": "redstone", "basePrice": 14 },
    { "itemId": "minecraft:piston", "rarity": 1, "category": "redstone", "basePrice": 12 },
    { "itemId": "minecraft:sticky_piston", "rarity": 1, "category": "redstone", "basePrice": 20 },
    { "itemId": "minecraft:observer", "rarity": 1, "category": "redstone", "basePrice": 16 },
    { "itemId": "minecraft:hopper", "rarity": 1, "category": "redstone", "basePrice": 18 },
    { "itemId": "minecraft:redstone_block", "rarity": 1, "category": "redstone", "basePrice": 90 },
    { "itemId": "minecraft:bucket", "rarity": 0, "category": "utility", "basePrice": 9 },
    { "itemId": "minecraft:shears", "rarity": 0, "category": "utility", "basePrice": 14 },
    { "itemId": "minecraft:fishing_rod", "rarity": 0, "category": "utility", "basePrice": 8 },
    { "itemId": "minecraft:torch", "rarity": 0, "category": "utility", "basePrice": 2 },
    { "itemId": "minecraft:lantern", "rarity": 0, "category": "utility", "basePrice": 8 },
    { "itemId": "minecraft:shulker_box", "rarity": 3, "category": "utility", "basePrice": 64 },
    { "itemId": "minecraft:ender_chest", "rarity": 2, "category": "utility", "basePrice": 36 },
    { "itemId": "minecraft:chest", "rarity": 0, "category": "utility", "basePrice": 8 },
    { "itemId": "minecraft:compass", "rarity": 1, "category": "utility", "basePrice": 14 },
    { "itemId": "minecraft:clock", "rarity": 1, "category": "utility", "basePrice": 18 },
    { "itemId": "minecraft:spyglass", "rarity": 1, "category": "utility", "basePrice": 12 },
    { "itemId": "minecraft:flint_and_steel", "rarity": 0, "category": "utility", "basePrice": 6 },
    { "itemId": "minecraft:crossbow", "rarity": 1, "category": "weapon", "basePrice": 18 },
    { "itemId": "minecraft:bow", "rarity": 0, "category": "weapon", "basePrice": 10 },
    { "itemId": "minecraft:arrow", "rarity": 0, "category": "weapon", "basePrice": 1 },
    { "itemId": "minecraft:trident", "rarity": 3, "category": "weapon", "basePrice": 72 },
    { "itemId": "minecraft:shield", "rarity": 1, "category": "armor", "basePrice": 12 },
    { "itemId": "minecraft:coal_block", "rarity": 0, "category": "ore", "basePrice": 36 },
    { "itemId": "minecraft:iron_block", "rarity": 1, "category": "metal", "basePrice": 108 },
    { "itemId": "minecraft:gold_block", "rarity": 2, "category": "metal", "basePrice": 162 },
    { "itemId": "minecraft:diamond_block", "rarity": 3, "category": "gems", "basePrice": 576 },
    { "itemId": "minecraft:emerald_block", "rarity": 3, "category": "gems", "basePrice": 432 },
    { "itemId": "minecraft:lapis_block", "rarity": 1, "category": "gems", "basePrice": 108 },
    { "itemId": "minecraft:copper_block", "rarity": 0, "category": "metal", "basePrice": 54 },
    { "itemId": "minecraft:amethyst_block", "rarity": 1, "category": "gems", "basePrice": 56 },
    { "itemId": "minecraft:netherite_block", "rarity": 3, "category": "metal", "basePrice": 648 }
  ]
}
""";
    }

    private CommodityConfigLoader() {}
}
