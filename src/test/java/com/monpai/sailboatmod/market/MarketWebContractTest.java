package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebContractTest {
    private static String webService() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
    }
    private static String webServer() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java"));
    }

    @Test
    void overviewJsonHasReceivingFields() throws Exception {
        String src = webService();
        assertTrue(src.contains("\"canChooseReceiving\""),
                "web overview JSON should include canChooseReceiving");
        assertTrue(src.contains("\"receivingWarehouseOptions\""),
                "web overview JSON should include receivingWarehouseOptions array");
    }
}
