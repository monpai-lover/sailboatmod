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

    @Test
    void marketForwardsAutoPickupToBuyerWarehouse() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("FulfillmentMode.AUTO_PICKUP"),
                "pickup loading should branch on AUTO_PICKUP to auto-depart the loaded vehicle");
        assertTrue(src.contains("routeLoadedVehicleToTarget("),
                "auto-pickup should reuse a 'send already-loaded vehicle to target warehouse' router (no re-load)");
        assertTrue(src.contains("resolveDispatchTerminalPlan("),
                "auto-pickup forwarding should reuse the seller-ship terminal/route planning");
    }

    @Test
    void dockExposesBuyerOwnedVehicleFilter() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/DockBlockEntity.java"));
        assertTrue(src.contains("availableBuyerVehiclesForPickup("),
                "dock should expose the buyer-owned idle vehicles available for auto-pickup dispatch");
        assertTrue(src.contains("PickupLock.vehicleBelongsToBuyer("),
                "buyer-owned vehicle filter should use PickupLock ownership matching");
    }

    @Test
    void autoPickupDeadheadsBuyerVehicleToSource() throws Exception {
        String src = marketBlockEntity();
        assertTrue(src.contains("dispatchAutoPickup("),
                "background dispatch should route the buyer's empty vehicle to the source terminal");
        assertTrue(src.contains("runBackgroundAutoDispatch") && src.contains("dispatchAutoPickup("),
                "dispatchAutoPickup should be wired into the background auto-dispatch entry");
        assertTrue(src.contains("PickupLock.isPickupOrder(") && src.contains("FulfillmentMode.AUTO_PICKUP"),
                "deadhead should scan AUTO_PICKUP locked orders for the source market");
    }

    @Test
    void vehiclesTriggerPickupLoadInZone() throws Exception {
        String sb = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        String cr = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        assertTrue(sb.contains("tryLoadPickupCargo("), "sailboat tick should trigger in-zone pickup loading (port)");
        assertTrue(cr.contains("tryLoadPickupCargo("), "carriage tick should trigger in-zone pickup loading (post station)");
    }
}
