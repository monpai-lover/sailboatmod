package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class MapTerrainRefreshScriptTest {
    @Test void selectiveTerrainRefreshPassesNodeTests() throws Exception {
        var process = new ProcessBuilder("node", "tools/test_map_terrain_refresh.js").redirectErrorStream(true).start();
        assertTrue(process.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
