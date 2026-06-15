package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class P2pOrderWiringContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void resolvesBuyerReceivingWarehouseCandidates() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("receivingWarehouseCandidatesFor("),
                "should resolve a buyer's available receiving warehouses");
        assertTrue(src.contains("defaultReceivingWarehouseFor("),
                "should resolve a buyer's default receiving warehouse for fallback");
        assertTrue(src.contains("getTownsForPlayer("),
                "candidate resolution should go through the buyer's own town (member-town binding)");
    }

    @Test
    void purchaseOrderUsesBuyerWarehouseNotHardcodedLinkedDock() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("resolveBuyerTargetWarehouse("),
                "order creation should resolve the buyer's chosen/default receiving warehouse");
        assertTrue(src.contains("FulfillmentMode.fromString("),
                "order creation should record the fulfillment mode");
    }

    @Test
    void purchasePacketAndWebCarryFulfillmentAndWarehouse() throws Exception {
        String packet = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/network/packet/PurchaseMarketListingPacket.java"));
        String web = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));

        assertTrue(packet.contains("fulfillment") && packet.contains("targetWarehousePos"),
                "purchase packet should carry fulfillment + target warehouse");
        assertTrue(web.contains("String fulfillment, net.minecraft.core.BlockPos targetWarehousePos"),
                "web purchase should accept fulfillment + target warehouse");
    }
}
