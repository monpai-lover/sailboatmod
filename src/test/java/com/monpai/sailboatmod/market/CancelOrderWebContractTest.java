package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelOrderWebContractTest {
    @Test
    void webRouteAndServiceExist() throws Exception {
        String server = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebServer.java"));
        assertTrue(server.contains("\"purchase-orders\".equals(path.get(3)) && \"cancel\".equals(path.get(5))"),
                "web server should route POST /purchase-orders/{id}/cancel");
        String service = Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
        assertTrue(service.contains("public boolean cancelPurchaseOrder("),
                "web service should expose cancelPurchaseOrder");
        assertTrue(service.contains("cancelPurchaseOrderById(identity.playerUuidString(), orderId).success()"),
                "web service should delegate to market.cancelPurchaseOrderById and read success");
    }
}
