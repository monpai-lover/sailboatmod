package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebApiRetryScriptTest {
    @Test
    void pendingMarketReadsRetryWithinTheirBoundAndRespectCancellation() throws Exception {
        Process process = new ProcessBuilder("node", "tools/test_marketweb_api_retry.js")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(10, TimeUnit.SECONDS), "market API retry tests timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
