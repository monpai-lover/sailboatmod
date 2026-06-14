package com.monpai.sailboatmod.entity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualTraceWiringContractTest {
    private static String sailboat() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
    }

    @Test
    void sailboatCreatesManualTraceWhenNoOrder() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("createOrUpdateManualTrace("),
                "sailboat should create a manual trace when manually dispatched without an order");
    }

    @Test
    void sailboatSyncsLivePositionPeriodically() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("updateLivePosition("),
                "sailboat tick should periodically push its live position to the trace");
        assertTrue(src.contains("TRACE_LIVE_SYNC_INTERVAL_TICKS"),
                "live sync should use a named interval constant (2s = 40 ticks)");
    }

    @Test
    void sailboatClearsManualTraceOnStop() throws Exception {
        String src = sailboat();
        assertTrue(src.contains("ShippingTraceService.removeTrace("),
                "sailboat should remove its manual trace when autopilot stops");
    }

    @Test
    void carriageCreatesSyncsAndClearsManualTrace() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        assertTrue(src.contains("createOrUpdateManualTrace("),
                "carriage should create a manual trace when manually dispatched");
        assertTrue(src.contains("updateLivePosition("),
                "carriage should sync live position to the trace");
        assertTrue(src.contains("ShippingTraceService.removeTrace("),
                "carriage should clear its manual trace on stop");
    }

    @Test
    void manualTraceCarriesDriverNationForSameNationVisibility() throws Exception {
        String sailboat = sailboat();
        String carriage = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        assertTrue(sailboat.contains("traceShipperNationId()") && sailboat.contains("getMember("),
                "sailboat manual trace should resolve the driver's nation for same-nation visibility");
        assertTrue(carriage.contains("traceShipperNationId()") && carriage.contains("getMember("),
                "carriage manual trace should resolve the driver's nation for same-nation visibility");
    }
}
