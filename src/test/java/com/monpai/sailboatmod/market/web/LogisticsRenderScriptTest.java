package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LogisticsRenderScriptTest {
    @Test
    void logisticsRenderLogicPassesNodeTests() throws Exception {
        assertScriptPasses("tools/test_logistics_render.js");
    }

    @Test
    void mapSessionLifecyclePassesNodeTests() throws Exception {
        assertScriptPasses("tools/test_map_session.js");
    }

    private static void assertScriptPasses(String script) throws Exception {
        Process process = new ProcessBuilder("node", script)
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS),
                "logistics render tests timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
