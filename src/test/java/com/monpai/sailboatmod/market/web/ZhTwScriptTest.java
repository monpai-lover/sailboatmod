package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZhTwScriptTest {
    @Test
    void traditionalChineseConverterPassesNodeTests() throws Exception {
        Process process = new ProcessBuilder("node", "tools/test_zh_tw.js")
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(20).toMillis(), TimeUnit.MILLISECONDS),
                "zh-TW converter tests timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
    }
}
