package com.monpai.sailboatmod.market;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebFrontendContractTest {
    private static String appJs() throws Exception {
        return Files.readString(Path.of("src/main/resources/marketweb/app.js"));
    }

    @Test
    void hasPurchaseModalAndProbe() throws Exception {
        String src = appJs();
        assertTrue(src.contains("openPurchaseModal"),
                "app.js should define openPurchaseModal");
        assertTrue(src.contains("/probe-modes"),
                "app.js should POST /probe-modes for mode reachability");
        assertTrue(src.contains("fulfillment") && src.contains("targetWarehouse"),
                "purchase POST should send fulfillment + targetWarehouse");
    }
}
