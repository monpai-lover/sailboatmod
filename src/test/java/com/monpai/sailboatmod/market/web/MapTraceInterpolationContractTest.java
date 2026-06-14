package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTraceInterpolationContractTest {
    private static String mapJs() throws Exception {
        return Files.readString(Path.of("src/main/resources/marketweb/map.js"));
    }

    @Test
    void pollIntervalIsTwoSeconds() throws Exception {
        assertTrue(mapJs().contains("SHIPMENT_REFRESH_MS = 2000"),
                "shipment polling should align to the 2s backend live sync");
    }

    @Test
    void usesCurrentPositionWithInterpolation() throws Exception {
        String js = mapJs();
        assertTrue(js.contains("shipment.current") || js.contains("s.current"),
                "front-end should consume the live current position");
        assertTrue(js.contains("function lerp") || js.contains("shipmentLivePoint"),
                "front-end should interpolate between polls for smooth motion");
    }

    @Test
    void distinguishesManualVehicleColor() throws Exception {
        assertTrue(mapJs().contains("shipment.manual"),
                "front-end should style manual shipments distinctly");
    }
}
