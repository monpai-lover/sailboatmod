package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentTraceDtoFieldsTest {
    @Test
    void shipmentTraceDtoHasCurrentAndManual() throws Exception {
        String dtos = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapDtos.java"));
        assertTrue(dtos.contains("Point current"),
                "ShipmentTrace should carry the live current position");
        assertTrue(dtos.contains("boolean manual"),
                "ShipmentTrace should carry the manual flag");
    }

    @Test
    void serviceExposesManualTraceAndLivePositionApis() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java"));
        assertTrue(service.contains("createOrUpdateManualTrace("),
                "service should create a manual trace for hand-dispatched vehicles");
        assertTrue(service.contains("updateLivePosition("),
                "service should update the live position of a trace");
        assertTrue(service.contains("manualTraceId("),
                "service should derive a stable manual trace id from the vehicle uuid");
    }

    @Test
    void toDtoOutputsCurrentAndManual() throws Exception {
        String service = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/logistics/ShippingTraceService.java"));
        assertTrue(service.contains("trace.currentX()") && service.contains("trace.currentZ()"),
                "toDto should map the live current position");
        assertTrue(service.contains("trace.manual()"),
                "toDto should map the manual flag");
    }

    @Test
    void jsonOutputsCurrentAndManual() throws Exception {
        String json = Files.readString(
                Path.of("src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapJson.java"));
        assertTrue(json.contains("\"manual\""),
                "shipment JSON should expose the manual flag");
        assertTrue(json.contains("\"current\""),
                "shipment JSON should expose the live current position");
    }
}
