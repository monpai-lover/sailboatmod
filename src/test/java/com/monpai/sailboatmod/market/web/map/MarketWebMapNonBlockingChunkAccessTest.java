package com.monpai.sailboatmod.market.web.map;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the critical invariant: web-map code must never synchronously request an unloaded chunk. */
class MarketWebMapNonBlockingChunkAccessTest {
    @Test
    void mapSnapshotUsesOnlyImmediateLoadedChunkLookup() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapChunkSnapshot.java"));
        assertTrue(source.contains("getChunkSource().getChunkNow("));
        assertFalse(source.contains("getChunkSource().getChunk("),
                "getChunk may wait for an in-progress chunk load on the server thread");
    }

    @Test
    void mapRenderAndRegionScanNeverSynchronouslyLoadChunks() throws IOException {
        for (String relative : new String[]{
                "src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRenderService.java",
                "src/main/java/com/monpai/sailboatmod/market/web/map/MarketWebMapRegionScanService.java"
        }) {
            String source = Files.readString(Path.of(relative));
            assertFalse(source.contains("getChunkSource().getChunk("),
                    relative + " must use non-blocking loaded-chunk lookups only");
        }
    }
}
