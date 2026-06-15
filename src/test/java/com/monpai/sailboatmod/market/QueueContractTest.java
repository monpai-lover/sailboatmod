package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueueContractTest {
    @Test
    void orderEntryHasQueueFields() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/MarketOverviewData.java"));
        assertTrue(src.contains("int queuePosition") && src.contains("int queueEtaSeconds"),
                "OrderEntry should carry queuePosition + queueEtaSeconds");
    }

    @Test
    void webMyOrdersEmitsQueueFields() throws Exception {
        String src = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        assertTrue(src.contains("\"queuePosition\"") && src.contains("\"queueEtaSeconds\""),
                "web myOrders should emit queue fields");
    }
}
