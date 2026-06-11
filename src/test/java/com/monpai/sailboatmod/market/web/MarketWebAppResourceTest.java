package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketWebAppResourceTest {
    private static final Path APP_JS = Path.of("src/main/resources/marketweb/app.js");

    @Test
    void appJavaScriptParsesInBrowserRuntime() throws Exception {
        Process process = new ProcessBuilder("node", "--check", APP_JS.toString())
                .redirectErrorStream(true)
                .start();
        assertTrue(process.waitFor(Duration.ofSeconds(10).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
                "node --check timed out");
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, process.exitValue(), output);
    }

    @Test
    void chineseLocaleContainsReadableCoreLabels() throws IOException {
        String appJs = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertFalse(appJs.contains("\u935c\u3127\u568e\u752f\u50da"),
                "Chinese locale still contains mojibake for online market");
        assertFalse(appJs.matches("(?s).*[\u6722\u7a22\u9722].*"),
                "Chinese locale still contains damaged Chinese characters from mojibake cleanup");
        assertFalse(appJs.contains("\u4e22\u4e2a"),
                "Chinese locale should not contain accidental wording from mojibake recovery");
        assertTrue(appJs.contains("language_label: \"\u8bed\u8a00\""),
                "Chinese locale should expose a readable language label");
        assertTrue(appJs.contains("bind_account: \"\u7ed1\u5b9a\u8d26\u6237\""),
                "Chinese locale should expose a readable bind account action");
        assertTrue(appJs.contains("guest_mode_ready: \"\u8bbf\u5ba2\u6a21\u5f0f"),
                "Chinese locale should explain guest mode in Chinese");
    }
}
