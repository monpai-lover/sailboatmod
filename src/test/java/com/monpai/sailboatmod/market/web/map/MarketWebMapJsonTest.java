package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.map.MarketWebMapDtos;

import com.google.gson.JsonObject;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMapJsonTest {
    @Test
    void snapshotJsonContainsStableLayerKeys() {
        MarketWebMapDtos.Snapshot snapshot = new MarketWebMapDtos.Snapshot(
                "minecraft:overworld",
                "#000000",
                256,
                1,
                new MarketWebMapDtos.Point(0.0D, 0.0D),
                7L,
                11L
        );

        JsonObject json = MarketWebMapJson.snapshot(snapshot);

        assertEquals("minecraft:overworld", json.get("dimensionId").getAsString());
        assertEquals("#000000", json.get("unknownColor").getAsString());
        assertEquals(256, json.get("tileSize").getAsInt());
        assertEquals(1, json.get("lod").getAsInt());
        assertTrue(json.has("defaultFocus"));
        assertEquals(7L, json.get("territoryRevision").getAsLong());
        assertEquals(11L, json.get("marketRevision").getAsLong());
    }

    @Test
    void snapshotPublishesThePredictionSamplerRevisionForBrowserCaching() {
        JsonObject json = MarketWebMapJson.snapshot(new MarketWebMapDtos.Snapshot(
                "minecraft:overworld", "#000000", 256, 1,
                new MarketWebMapDtos.Point(0, 0), 0L, 0L));

        assertTrue(json.has("tellusRenderVersion"));
        assertEquals(2, json.get("tellusRenderVersion").getAsInt());
    }

    @Test
    void territoryFlagUrlEscapesFlagIdAsPathSegment() {
        NationRecord nation = new NationRecord("crimea", "Crimea", "CRM", 0x2255AA, 0xF2C14E,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L, "port", "minecraft:overworld", 0L, "crimea flag/one");
        TownRecord town = new TownRecord("port", "crimea", "Crimea Port",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1L, "minecraft:overworld", 0L, "", "european");
        NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 10, -4, "crimea", "port",
                "member", "member", "member", "member", "member", "member", "member", 100L, NationClaimRecord.SOURCE_MANUAL);

        JsonObject json = MarketWebMapJson.territory(MarketWebMapLayerService.toTerritoryForTest(claim, nation, town));

        assertEquals("/api/map/flags/crimea%20flag%2Fone.png", json.get("flagUrl").getAsString());
    }
    @Test
    void shipmentsCarryTheirVehicleKind() {
        com.google.gson.JsonObject rail = MarketWebMapJson.shipment(new com.monpai.sailboatmod.market.map.MarketWebMapDtos.ShipmentTrace(
                "o1", "", "RAIL", "IN_TRANSIT", "A", "B", "", "", "", "", 0, 0.0D, java.util.List.of(), java.util.List.of(),
                0, 0.0D, null, false));
        com.google.gson.JsonObject legacy = MarketWebMapJson.shipment(new com.monpai.sailboatmod.market.map.MarketWebMapDtos.ShipmentTrace(
                "o2", "", "PORT", "SAILING", "A", "B", "", "", "", "", 0, 0.0D, java.util.List.of(), java.util.List.of(),
                0, 0.0D, null, false));

        org.junit.jupiter.api.Assertions.assertEquals("train", rail.get("vehicleKind").getAsString());
        org.junit.jupiter.api.Assertions.assertEquals("sailboat", legacy.get("vehicleKind").getAsString());
    }

    @Test
    void shipmentsNameTheWaypointTheVehicleIsHeadingTo() {
        com.google.gson.JsonObject json = MarketWebMapJson.shipment(new com.monpai.sailboatmod.market.map.MarketWebMapDtos.ShipmentTrace(
                "o1", "", "PORT", "SAILING", "A", "B", "", "", "", "", 0, 0.0D, java.util.List.of(), java.util.List.of(),
                4, 0.0D, null, false));

        org.junit.jupiter.api.Assertions.assertEquals(4, json.get("targetPointIndex").getAsInt());
        // kept for pages and tools written against the old field
        org.junit.jupiter.api.Assertions.assertEquals(4, json.get("completedPointCount").getAsInt());
    }

    @Test
    void timelineEventsCarryKindTimeAndPlace() {
        com.google.gson.JsonArray json = MarketWebMapJson.events(java.util.List.of(
                new com.monpai.sailboatmod.market.logistics.ShippingTraceEvent("DEPARTED", 100L, 1.5D, -2.0D),
                new com.monpai.sailboatmod.market.logistics.ShippingTraceEvent("STUCK", 260L, 40.0D, 8.0D)));

        assertEquals(2, json.size());
        assertEquals("DEPARTED", json.get(0).getAsJsonObject().get("kind").getAsString());
        assertEquals(260L, json.get(1).getAsJsonObject().get("gameTime").getAsLong());
        assertEquals(40.0D, json.get(1).getAsJsonObject().get("x").getAsDouble());
        assertEquals(8.0D, json.get(1).getAsJsonObject().get("z").getAsDouble());
    }

}
