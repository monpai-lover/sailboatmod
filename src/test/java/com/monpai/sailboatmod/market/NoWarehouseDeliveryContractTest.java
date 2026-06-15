package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoWarehouseDeliveryContractTest {
    @Test
    void deliverDockArrivalFallsBackToDockStorage() throws Exception {
        String src = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/nation/service/TownDeliveryService.java"));
        assertTrue(src.contains("dock.insertCargo(cargo)"),
                "deliverDockArrival should unload to the arriving dock/station's own storage when no town warehouse");
        // 仅在无 town（没检查到仓库）时回退
        assertTrue(src.contains("townId.isBlank()") || src.contains("!townId.isBlank()"),
                "fallback should be gated on missing town id");
    }
}
