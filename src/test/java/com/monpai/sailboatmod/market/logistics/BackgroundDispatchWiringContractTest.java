package com.monpai.sailboatmod.market.logistics;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackgroundDispatchWiringContractTest {
    @Test
    void marketHasBackgroundAutoDispatchEntry() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(src.contains("public void runBackgroundAutoDispatch()"),
                "market should expose a background auto-dispatch entry");
        assertTrue(src.contains("TransportDispatchService") && src.contains("isBackgroundDispatchable("),
                "background dispatch should gate on dispatch eligibility");
        assertTrue(src.contains("tryAutoDispatchOrders("),
                "background dispatch should reuse the existing auto-dispatch chain");
    }

    @Test
    void dispatchGatesOutRealPickup() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/block/entity/MarketBlockEntity.java"));
        assertTrue(src.contains("needsVehicleDispatch()"),
                "dispatch grouping should skip real-pickup orders (no vehicle dispatch)");
    }

    @Test
    void serviceHasGlobalTickIteratingMarkets() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/TransportDispatchService.java"));
        assertTrue(src.contains("public static void globalTick("),
                "service should expose a globalTick entry");
        assertTrue(src.contains("runBackgroundAutoDispatch()"),
                "globalTick should drive each market's background dispatch");
        assertTrue(src.contains("MarketRegistry.get(") && src.contains("getAllLevels()"),
                "globalTick should iterate registered markets across loaded levels");
    }

    @Test
    void serverTickDrivesDispatchEvery15Seconds() throws Exception {
        String src = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/ServerEvents.java"));
        assertTrue(src.contains("TransportDispatchService.globalTick("),
                "server tick should drive background dispatch");
        assertTrue(src.contains("dispatchTickCounter"),
                "dispatch should run on its own low-frequency counter");
        assertTrue(src.contains(">= 300"),
                "dispatch counter should fire every 15s (300 ticks)");
    }
}
