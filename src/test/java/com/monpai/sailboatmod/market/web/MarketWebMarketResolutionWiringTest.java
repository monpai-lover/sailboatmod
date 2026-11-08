package com.monpai.sailboatmod.market.web;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MarketWebMarketResolutionWiringTest {
    private static String service() throws Exception {
        return Files.readString(Path.of("src/main/java/com/monpai/sailboatmod/market/web/MarketWebService.java"));
    }

    @Test
    void remoteMarketResolutionDoesNotWaitForDiskOrSynchronouslyLoadChunks() throws Exception {
        String source = service();
        int start = source.indexOf("public ResolvedMarket resolveMarket(MinecraftServer server, String dimensionId, BlockPos pos)");
        String resolution = source.substring(start, source.indexOf("private JsonArray receivingWarehouseOptions", start));
        assertFalse(resolution.contains("readSafeOnMainThread"), "remote markets must use retained asynchronous probes");
        assertFalse(resolution.contains("getChunkAt("), "a verified saved market still needs asynchronous activation");
    }

    @Test
    void catalogListingOnlyReportsAlreadyLoadedMarkets() throws Exception {
        String source = service();
        int start = source.indexOf("public JsonArray listMarkets(");
        String listing = source.substring(start, source.indexOf("public JsonObject mapSnapshot", start));
        assertFalse(listing.contains("resolveMarket("), "listing every market must not activate remote chunks");
    }

    @Test
    void pendingMarketStateSurvivesTheEncodedIdEntryPoint() throws Exception {
        String source = service();
        int start = source.indexOf("public ResolvedMarket resolveMarket(MinecraftServer server, String marketId)");
        String resolution = source.substring(start, source.indexOf("public ResolvedMarket resolveMarket(MinecraftServer server, String dimensionId", start));
        assertFalse(resolution.contains("catch (Exception"), "temporary unavailability must reach the HTTP retry response");
    }
}
