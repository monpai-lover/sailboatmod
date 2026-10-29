package com.monpai.sailboatmod.market.web;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebTellusEndpointTest {
    @Test
    void worldInfoIsNotTellusWithoutTellusLevel() {
        JsonObject info = new MarketWebService().worldInfo(null);

        assertFalse(info.get("tellus").getAsBoolean());
        assertEquals(0.0D, info.get("worldScale").getAsDouble());
        assertEquals(-1, info.get("tellusTileMaxZoom").getAsInt());
    }

    @Test
    void tellusTileIsNullOutsideTellus() {
        assertNull(new MarketWebService().tellusTile(null, 0, 0, 0));
    }

    @Test
    void serverRegistersTellusRoutes() throws Exception {
        String server = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java"));

        assertTrue(server.contains("\"/api/map/world\""));
        assertTrue(server.contains("\"/api/map/tellus/tile\""));
        assertTrue(server.contains("\"/tellus-basemap.js\""));
    }

    @Test
    void tellusQueueStopsWithTheServer() throws Exception {
        String events = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebEvents.java"));

        assertTrue(events.contains("MarketWebTellusTileQueue.shutdownGlobal()"));
    }
}
