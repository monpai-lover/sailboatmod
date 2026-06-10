package com.monpai.sailboatmod.registry;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModCreativeTabResourceTest {
    private static final List<String> EXPECTED_CREATIVE_TAB_ITEMS = List.of(
            "SAILBOAT_ITEM",
            "CARRIAGE_ITEM",
            "ROUTE_BOOK_ITEM",
            "POST_ROUTE_BOOK_ITEM",
            "ROAD_PLANNER_ITEM",
            "BUILDER_HAMMER_ITEM",
            "COMMAND_BATON_ITEM",
            "DOCK_ITEM",
            "POST_STATION_ITEM",
            "TOWN_WAREHOUSE_ITEM",
            "MARKET_ITEM",
            "TOWN_CORE_ITEM",
            "NATION_CORE_ITEM",
            "NATION_FLAG_ITEM",
            "TOWN_FLAG_ITEM",
            "BANK_ITEM",
            "BANK_CONSTRUCTOR_ITEM",
            "COTTAGE_ITEM",
            "BAR_ITEM",
            "BARRACKS_ITEM",
            "WORKSTATION_ITEM",
            "SCHOOL_ITEM",
            "HALF_NUGGET_ITEM"
    );

    @Test
    void modCreativeTabShowsAllCoreItems() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/registry/ModCreativeTabs.java"),
                StandardCharsets.UTF_8
        );

        for (String itemField : EXPECTED_CREATIVE_TAB_ITEMS) {
            assertTrue(source.contains("output.accept(ModItems." + itemField + ".get())"),
                    "Creative tab is missing " + itemField);
        }
    }
}
