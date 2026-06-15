package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PickupWiringContractTest {
    private static String marketBlockEntity() throws Exception {
        return Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
    }

    @Test
    void pickupOrdersAreCreatedLocked() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("PickupLock.STATUS_LOCKED"),
                "pickup-mode purchases should create orders in PICKUP_LOCKED status");
        assertTrue(src.contains("resolvedMode == FulfillmentMode.REAL_PICKUP"),
                "order status should branch on fulfillment mode");
    }

    @Test
    void marketHasPickupLoadCore() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("tryLoadPickupCargo("),
                "market should expose a pickup-cargo loading core shared by real/auto pickup");
        assertTrue(src.contains("PickupLock.isPickupOrder("),
                "pickup loading should match locked pickup orders via PickupLock");
        assertTrue(src.contains("splitCargo(") && src.contains("splitOrderForShipment("),
                "pickup loading should reuse the seller-ship split logic (template-based, not warehouse extract)");
    }

    @Test
    void dockDelegatesPickupLoadToMarket() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java"));
        assertTrue(src.contains("tryLoadPickupCargo("),
                "dock should expose an in-zone pickup trigger that delegates to the market core");
    }
}
