package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapProjectionScriptTest {
    @Test
    void canvasAndLeafletStayAlignedAcrossDisplayScales() throws Exception {
        Process process = new ProcessBuilder("node", "tools/test_map_projection.js")
                .redirectErrorStream(true)
                .start();
        try {
            assertTrue(process.waitFor(20, TimeUnit.SECONDS), "map projection tests timed out");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
