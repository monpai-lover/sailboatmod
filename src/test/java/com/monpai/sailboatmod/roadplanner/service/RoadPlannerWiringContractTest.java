package com.monpai.sailboatmod.roadplanner.service;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Wiring that needs a running server; checked in the source. */
class RoadPlannerWiringContractTest {
    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path)).replace("\r\n", "\n");
    }

    @Test
    void aChunkCountsAsLoadedOnlyOnceItIsReallyThere() throws Exception {
        String tickets = read("src/main/java/com/monpai/sailboatmod/roadplanner/service/RoadPlannerChunkTickets.java");
        // hasChunk only looks at the ticket level; reading the height of such a chunk generates it on the main thread
        assertTrue(tickets.contains("getChunkNow("));
        assertFalse(tickets.contains("hasChunk("));
    }

    @Test
    void aNewRequestReplacesTheOldPreviewAndConfirmWaitsForLoading() throws Exception {
        String request = read("src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerPreviewRequestPacket.java");
        String confirm = read("src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerConfirmBuildPacket.java");
        String menu = read("src/main/java/com/monpai/sailboatmod/network/packet/roadplanner/RoadPlannerMenuActionPacket.java");
        int submit = request.indexOf("jobs.submitStreaming(");
        assertTrue(submit > 0, "the real request starts a streamed preview");
        assertTrue(request.lastIndexOf(".cancelPreview(", submit) > 0, "the old preview is dropped before loading the new one");
        int complete = request.indexOf("}, () -> {", submit);
        int attach = request.indexOf(".attachStagedPlan(", submit);
        int ready = request.indexOf("accumulator.snapshot(true)", submit);
        assertTrue(complete > submit && attach > complete && ready > attach,
                "only the completion callback attaches the full staged plan and marks it ready");
        assertTrue(confirm.contains("RoadPlannerPreviewJobService.global().pendingFor("), "no confirming while a preview loads");
        assertTrue(menu.contains("RoadPlannerPreviewJobService.global().cancel("), "returning to the planner stops a loading preview");
    }

    @Test
    void jobsAndCachedPreviewsAreDroppedOnLogoutAndServerStop() throws Exception {
        String events = read("src/main/java/com/monpai/sailboatmod/ServerEvents.java");
        assertTrue(events.contains("RoadPlannerPreviewJobService.global().clear()"));
        assertTrue(events.contains("RoadPlannerChunkTickets.clearAll()"));
        assertTrue(events.contains("PlayerLoggedOutEvent") && events.contains(".forgetPlayer("));
    }

    @Test
    void theWebMapCacheIsUpgradedBeforeTheWebServerAcceptsRequests() throws Exception {
        String web = read("src/main/java/com/monpai/sailboatmod/market/web/MarketWebEvents.java");
        assertTrue(web.indexOf("clearStaleServerChunksAfterRendererUpgrade") < web.indexOf("MarketWebServer.start("));
    }
}
