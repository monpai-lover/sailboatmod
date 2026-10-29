package com.monpai.sailboatmod.transport;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

class ControllerContentTest {
    @Test
    void bothControllersHaveExactZeroMassHighPriorityDefinitions() throws Exception {
        for (String id :
                new String[] {
                    "vessel_registration_controller", "land_vehicle_registration_controller"
                }) {
            Path file =
                    Path.of(
                            "src/main/resources/data/sailboatmod/physics_block_properties/"
                                    + id
                                    + ".json");
            assertTrue(Files.isRegularFile(file), id);
            var json = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            assertEquals("sailboatmod:" + id, json.get("selector").getAsString());
            assertTrue(json.get("priority").getAsInt() > 1000);
            assertEquals(0.0D, json.getAsJsonObject("properties").get("sable:mass").getAsDouble());
        }
    }

    @Test
    void bothControllersHaveModelsDropsRecipesAndLocalizedNames() throws Exception {
        for (String id :
                new String[] {
                    "vessel_registration_controller", "land_vehicle_registration_controller"
                }) {
            for (String relative :
                    new String[] {
                        "assets/sailboatmod/blockstates/" + id + ".json",
                        "assets/sailboatmod/models/block/" + id + ".json",
                        "assets/sailboatmod/models/item/" + id + ".json",
                        "data/sailboatmod/loot_table/blocks/" + id + ".json",
                        "data/sailboatmod/recipe/" + id + ".json"
                    }) {
                assertTrue(Files.isRegularFile(Path.of("src/main/resources", relative)), relative);
            }
            for (String language : new String[] {"en_us", "zh_cn"}) {
                var json =
                        JsonParser.parseString(
                                        Files.readString(
                                                Path.of(
                                                        "src/main/resources/assets/sailboatmod/lang/"
                                                                + language
                                                                + ".json")))
                                .getAsJsonObject();
                assertTrue(json.has("block.sailboatmod." + id));
            }
        }
    }
}
