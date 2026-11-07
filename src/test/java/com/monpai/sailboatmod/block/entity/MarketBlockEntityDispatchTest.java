package com.monpai.sailboatmod.block.entity;

import com.monpai.sailboatmod.market.commodity.CommodityDefinition;
import com.monpai.sailboatmod.transport.LogisticsCarrier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketBlockEntityDispatchTest {
    @Test
    void nativeTrainUsesRailFreightTerminalAndOrdinaryLandCarrierUsesPostStation() {
        assertEquals("RAIL", MarketBlockEntity.terminalKindForCarrier(landCarrier("create-train")).name());
        assertEquals("POST_STATION", MarketBlockEntity.terminalKindForCarrier(landCarrier("entity")).name());
    }

    private static LogisticsCarrier landCarrier(String providerId) {
        return (LogisticsCarrier) Proxy.newProxyInstance(LogisticsCarrier.class.getClassLoader(),
                new Class<?>[]{LogisticsCarrier.class}, (proxy, method, arguments) -> switch (method.getName()) {
                    case "providerId" -> providerId;
                    case "transportKind" -> LogisticsCarrier.Kind.LAND;
                    default -> throw new AssertionError("Unexpected carrier call: " + method.getName());
                });
    }

    @Test
    void offlineOverviewKeepsDispatchableCarrierEvenWhenDockOwnerDiffers() {
        UUID dockOwner = UUID.randomUUID();
        UUID carrierOwner = UUID.randomUUID();

        assertTrue(MarketBlockEntity.includeCarrierInOfflineDispatchOverviewForTest(dockOwner, carrierOwner));
    }

    @Test
    void activeBuyOrderCommoditiesContributeToWebCatalogWithoutOverwritingListings() {
        Map<String, String> displayNames = new LinkedHashMap<>();
        displayNames.put("minecraft:oak_log", "Existing Oak");
        List<CommodityDefinition> activeDemand = List.of(
                new CommodityDefinition("minecraft:oak_log", "minecraft:oak_log", "minecraft:oak_log", "Oak Log", 1, "wood", true, 0, 1, 1, 1, 100),
                new CommodityDefinition("minecraft:emerald", "minecraft:emerald", "minecraft:emerald", "Emerald", 1, "gems", true, 2, 2, 1, 1, 100)
        );

        MarketBlockEntity.addBuyOrderCommodityDisplayNamesForTest(displayNames, activeDemand);

        assertEquals("Existing Oak", displayNames.get("minecraft:oak_log"));
        assertEquals("Emerald", displayNames.get("minecraft:emerald"));
    }
}
