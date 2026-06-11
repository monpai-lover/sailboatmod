package com.monpai.sailboatmod.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientKeyMappingsTest {
    @Test
    void uiOpenKeysUseModControlsCategory() throws IOException {
        String keyMappings = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/ClientKeyMappings.java"));

        assertTrue(keyMappings.contains("key.categories.sailboatmod"));
        assertTrue(keyMappings.contains("OPEN_SAILBOAT_INFO"));
        assertTrue(keyMappings.contains("OPEN_NATION_MENU"));
        assertFalse(keyMappings.contains("key.categories.gameplay"));
        assertFalse(keyMappings.contains("TRANSPORT_"));
        assertFalse(keyMappings.contains("transport_"));
    }

    @Test
    void uiOpenKeysAreRegisteredAndTranslated() throws IOException {
        String keyMappings = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/ClientKeyMappings.java"));
        String enUs = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/en_us.json"));
        String zhCn = Files.readString(Path.of("src/main/resources/assets/sailboatmod/lang/zh_cn.json"));

        assertTrue(keyMappings.contains("event.register(OPEN_SAILBOAT_INFO);"));
        assertTrue(keyMappings.contains("event.register(OPEN_NATION_MENU);"));
        assertTrue(enUs.contains("\"key.categories.sailboatmod\""));
        assertTrue(enUs.contains("\"key.sailboatmod.open_info\""));
        assertTrue(enUs.contains("\"key.sailboatmod.open_nation_menu\""));
        assertTrue(zhCn.contains("\"key.categories.sailboatmod\""));
        assertTrue(zhCn.contains("\"key.sailboatmod.open_info\""));
        assertTrue(zhCn.contains("\"key.sailboatmod.open_nation_menu\""));
    }

    @Test
    void vehicleMovementStillUsesVanillaMovementKeys() throws IOException {
        String inputHandler = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/client/ClientInputHandler.java"));

        assertTrue(inputHandler.contains("minecraft.options.keyUp.isDown()"));
        assertTrue(inputHandler.contains("minecraft.options.keyDown.isDown()"));
        assertTrue(inputHandler.contains("minecraft.options.keyLeft.isDown()"));
        assertTrue(inputHandler.contains("minecraft.options.keyRight.isDown()"));
    }
}
