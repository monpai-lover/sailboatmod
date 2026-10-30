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

    @Test
    void persistedFlightTelemetryReachesJsonWithoutChangingOrderStatus() {
        var original = new com.monpai.sailboatmod.market.logistics.ShippingTraceRecord(
                "air-1", "owner", "minecraft:overworld", "AIRPLANE", "IN_TRANSIT", "", "", "A", "B",
                java.util.List.of(new net.minecraft.world.phys.Vec3(1, 70, 2), new net.minecraft.world.phys.Vec3(100, 90, 2)),
                1, 0.5, 100, 200, 50, 2, 1.2, java.util.List.of(), false);
        var tag = original.save();
        var flight = new net.minecraft.nbt.CompoundTag();
        flight.putDouble("WorldY", -12.5);
        flight.putString("FlightState", "CRUISING");
        flight.putString("ExecutionMode", "VIRTUAL");
        tag.put("Flight", flight);
        var loaded = com.monpai.sailboatmod.market.logistics.ShippingTraceRecord.load(tag).withProgress(1, 0.6, 220);
        var json = MarketWebMapJson.shipment(com.monpai.sailboatmod.market.logistics.ShippingTraceService.toDto(loaded, null, null, null));
        assertTrue(json.has("flight"), "persisted flight telemetry must survive updates and reach the web");
        assertEquals(-12.5, json.getAsJsonObject("flight").get("worldY").getAsDouble());
        assertEquals("CRUISING", json.getAsJsonObject("flight").get("flightState").getAsString());
        assertEquals("VIRTUAL", json.getAsJsonObject("flight").get("executionMode").getAsString());
        assertEquals("IN_TRANSIT", json.get("status").getAsString());
        assertEquals("CRUISING", loaded.save().getCompound("Flight").getString("FlightState"));
    }

    @Test
    void airportJsonIncludesRegisteredRunwayGeometryWithoutClaimingCurrentAvailability() {
        var facility = new com.monpai.sailboatmod.transport.air.airport.AirportFacility("09",
                com.monpai.sailboatmod.transport.air.airport.AirportFacility.Type.RUNWAY,
                new net.minecraft.core.BlockPos(10, 72, 20), new net.minecraft.core.BlockPos(130, 72, 20), 12, 24);
        var airport = new com.monpai.sailboatmod.transport.air.airport.AirportSavedData.AirportSnapshot(
                "minecraft:overworld", facility.start(), "Player Airport", "town", "private-owner", java.util.List.of(facility));
        var json = MarketWebMapJson.airports(java.util.List.of(airport)).get(0).getAsJsonObject();
        assertEquals("Player Airport", json.get("name").getAsString());
        org.junit.jupiter.api.Assertions.assertFalse(json.has("ownerUuid"));
        var runway = json.getAsJsonArray("facilities").get(0).getAsJsonObject();
        assertEquals("RUNWAY", runway.get("type").getAsString());
        assertEquals(130, runway.getAsJsonObject("end").get("x").getAsInt());
        assertEquals(72, runway.getAsJsonObject("start").get("y").getAsInt());
        assertEquals(12, runway.get("width").getAsInt());
        assertEquals("not_checked", runway.get("availability").getAsString());
    }

}
