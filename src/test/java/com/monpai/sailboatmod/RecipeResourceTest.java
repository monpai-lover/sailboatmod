package com.monpai.sailboatmod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeResourceTest {
    private static final Path RECIPE_DIR = Path.of("src/main/resources/data/sailboatmod/recipes");

    private static final Map<String, String> CORE_CRAFTABLE_ITEMS = Map.ofEntries(
            Map.entry("sailboat", "sailboatmod:sailboat"),
            Map.entry("carriage", "sailboatmod:carriage"),
            Map.entry("route_book", "sailboatmod:route_book"),
            Map.entry("post_route_book", "sailboatmod:post_route_book"),
            Map.entry("road_planner", "sailboatmod:road_planner"),
            Map.entry("builder_hammer", "sailboatmod:builder_hammer"),
            Map.entry("command_baton", "sailboatmod:command_baton"),
            Map.entry("bank_constructor", "sailboatmod:bank_constructor"),
            Map.entry("bank_block", "sailboatmod:bank_block"),
            Map.entry("nation_core", "sailboatmod:nation_core"),
            Map.entry("town_core", "sailboatmod:town_core"),
            Map.entry("market", "sailboatmod:market"),
            Map.entry("post_station", "sailboatmod:post_station"),
            Map.entry("dock", "sailboatmod:dock")
    );

    private static final String[] NON_CRAFTED_SETTLEMENT_BUILDINGS = {
            "cottage",
            "bar",
            "barracks",
            "workstation",
            "school"
    };

    @Test
    void coreCraftableItemsHaveRecipeFilesWithExpectedResults() throws IOException {
        for (Map.Entry<String, String> entry : CORE_CRAFTABLE_ITEMS.entrySet()) {
            Path recipePath = RECIPE_DIR.resolve(entry.getKey() + ".json");
            assertTrue(Files.isRegularFile(recipePath), "Missing recipe for " + entry.getValue());

            JsonObject recipe = JsonParser.parseString(Files.readString(recipePath, StandardCharsets.UTF_8)).getAsJsonObject();
            String type = recipe.get("type").getAsString();
            assertTrue(type.equals("minecraft:crafting_shaped") || type.equals("minecraft:crafting_shapeless"),
                    "Recipe must be a crafting recipe: " + recipePath);
            assertEquals(entry.getValue(), recipe.getAsJsonObject("result").get("item").getAsString(),
                    "Wrong recipe output for " + recipePath);
        }
    }

    @Test
    void settlementBuildingsDoNotHaveStandaloneCraftingRecipes() {
        for (String recipeName : NON_CRAFTED_SETTLEMENT_BUILDINGS) {
            assertTrue(Files.notExists(RECIPE_DIR.resolve(recipeName + ".json")),
                    "Settlement building should not have a standalone crafting recipe: " + recipeName);
        }
    }
}
