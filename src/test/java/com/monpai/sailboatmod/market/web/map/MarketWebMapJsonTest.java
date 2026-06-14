package com.monpai.sailboatmod.market.web.map;

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
}
