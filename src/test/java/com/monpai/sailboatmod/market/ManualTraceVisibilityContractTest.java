package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManualTraceVisibilityContractTest {
    @Test
    void sailboatTraceFallsBackToOwner() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/SailboatEntity.java"));
        int idx = src.indexOf("private String traceShipperUuid()");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("getOwnerUuid()"),
                "SailboatEntity traceShipperUuid should fall back to owner when no driver (manual dispatch)");
    }

    @Test
    void carriageTraceFallsBackToOwner() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/entity/CarriageEntity.java"));
        int idx = src.indexOf("private String traceShipperUuid()");
        int end = src.indexOf("\n    }", idx);
        String body = src.substring(idx, end);
        assertTrue(body.contains("getOwnerUuid()"),
                "CarriageEntity traceShipperUuid should fall back to owner when no driver (manual dispatch)");
    }

    @Test
    void placingSailboatSetsOwner() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/item/SailboatItem.java"));
        assertTrue(src.contains("sailboat.initializeOwnerIfAbsent(player)"),
                "placing a sailboat should set the placer as owner");
    }
}
