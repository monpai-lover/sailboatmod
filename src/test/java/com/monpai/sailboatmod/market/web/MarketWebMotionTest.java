package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebMotionTest {
    @Test
    void marketStripMotionCallbacksRespectStateAndPreferences() throws Exception {
        Process process = new ProcessBuilder("node", "tools/test_marketweb_motion.js")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS),
                "market strip motion tests timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
