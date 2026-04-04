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
    { "itemId": "minecraft:diamond",         "rarity": 3, "category": "gems",   "basePrice": 64 },
    { "itemId": "minecraft:emerald",          "rarity": 3, "category": "gems",   "basePrice": 48 },
    { "itemId": "minecraft:netherite_ingot",  "rarity": 3, "category": "metal",  "basePrice": 72 },
    { "itemId": "minecraft:netherite_scrap",  "rarity": 2, "category": "metal",  "basePrice": 40 },
    { "itemId": "minecraft:gold_ingot",       "rarity": 1, "category": "metal",  "basePrice": 18 },
    { "itemId": "minecraft:iron_ingot",       "rarity": 1, "category": "metal",  "basePrice": 12 },
    { "itemId": "minecraft:coal",             "rarity": 0, "category": "ore",    "basePrice": 4  },
    { "itemId": "minecraft:wheat",            "rarity": 0, "category": "food",   "basePrice": 2  },
    { "itemId": "minecraft:bread",            "rarity": 0, "category": "food",   "basePrice": 4  }
  ]
}
""";
    }

    private CommodityConfigLoader() {}
}
