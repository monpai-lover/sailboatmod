package com.monpai.sailboatmod.market.web.map;

import com.monpai.sailboatmod.market.terminal.MarketTerminalSavedData;
import com.monpai.sailboatmod.market.web.MarketWebService;
import com.monpai.sailboatmod.nation.model.NationClaimRecord;
import com.monpai.sailboatmod.nation.model.NationRecord;
import com.monpai.sailboatmod.nation.model.TownRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MarketWebMapLayerServiceTest {
    @Test
    void territoryUsesNationPrimaryAndSecondaryColors() {
        NationRecord nation = new NationRecord("crimea", "Crimea", "CRM", 0x2255AA, 0xF2C14E,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L, "port", "minecraft:overworld", 0L, "crimea_flag");
        TownRecord town = new TownRecord("port", "crimea", "Crimea Port",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1L, "minecraft:overworld", 0L, "town_flag", "european");
        NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 10, -4, "crimea", "port",
                "member", "member", "member", "member", "member", "member", "member", 100L, NationClaimRecord.SOURCE_MANUAL);

        MarketWebMapDtos.Territory dto = MarketWebMapLayerService.toTerritoryForTest(claim, nation, town);

        assertEquals(0x2255AA, dto.fillRgb());
        assertEquals(0xF2C14E, dto.borderRgb());
        assertEquals("crimea_flag", dto.flagId());
        assertEquals("Crimea", dto.nationName());
        assertEquals("Crimea Port", dto.townName());
    }

    @Test
    void territoryFallsBackToTownFlagWhenNationFlagIsMissing() {
        NationRecord nation = new NationRecord("crimea", "Crimea", "CRM", 0x2255AA, 0xF2C14E,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L, "port", "minecraft:overworld", 0L, "");
        TownRecord town = new TownRecord("port", "crimea", "Crimea Port",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1L, "minecraft:overworld", 0L, "town_flag", "european");
        NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 10, -4, "crimea", "port",
                "member", "member", "member", "member", "member", "member", "member", 100L, NationClaimRecord.SOURCE_MANUAL);

        MarketWebMapDtos.Territory dto = MarketWebMapLayerService.toTerritoryForTest(claim, nation, town);

        assertEquals("town_flag", dto.flagId());
    }

    @Test
    void territoryBorderFallsBackToPrimaryColorWhenSecondaryIsUnset() {
        NationRecord nation = new NationRecord("crimea", "Crimea", "CRM", 0x2255AA, 0,
                UUID.fromString("00000000-0000-0000-0000-000000000001"), 1L, "port", "minecraft:overworld", 0L, "crimea_flag");
        TownRecord town = new TownRecord("port", "crimea", "Crimea Port",
                UUID.fromString("00000000-0000-0000-0000-000000000002"), 1L, "minecraft:overworld", 0L, "town_flag", "european");
        NationClaimRecord claim = new NationClaimRecord("minecraft:overworld", 10, -4, "crimea", "port",
                "member", "member", "member", "member", "member", "member", "member", 100L, NationClaimRecord.SOURCE_MANUAL);

        MarketWebMapDtos.Territory dto = MarketWebMapLayerService.toTerritoryForTest(claim, nation, town);

        assertEquals(0x2255AA, dto.borderRgb());
    }

    @Test
    void marketMarkerIdMatchesMarketWebEncodingWithoutResolvingChunks() {
        MarketTerminalSavedData.MarketTerminalEntry entry = new MarketTerminalSavedData.MarketTerminalEntry(
                "minecraft:overworld", new BlockPos(12, 64, -30), "North Market", "uuid-1", "Alice");

        MarketWebMapDtos.MarketMarker marker = MarketWebMapLayerService.toMarketMarkerForTest(entry);

        assertEquals(MarketWebService.encodeMarketId("minecraft:overworld", new BlockPos(12, 64, -30)), marker.marketId());
        assertEquals("North Market", marker.marketName());
        assertEquals(12, marker.x());
        assertEquals(-30, marker.z());
    }

    @Test
    void defaultFocusUsesAverageMarketPositionWhenNoSelectedMarketExists() {
        List<MarketWebMapDtos.MarketMarker> markets = List.of(
                new MarketWebMapDtos.MarketMarker("a", "A", "Alice", "uuid-a", "minecraft:overworld", 0, 64, 0, "", ""),
                new MarketWebMapDtos.MarketMarker("b", "B", "Bob", "uuid-b", "minecraft:overworld", 32, 64, 16, "", "")
        );

        MarketWebMapDtos.Point focus = MarketWebMapLayerService.defaultFocusForTest(markets, null, null);

        assertEquals(16.0D, focus.x(), 0.0001D);
        assertEquals(8.0D, focus.z(), 0.0001D);
    }
}
