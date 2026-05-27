package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.OpenRoadMergeCandidatesPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadMapSnapshotSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadProgressPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapPreloadRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerMapTileSyncPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerPreviewRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlayRequestPacket;
import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import gg.essential.elementa.WindowScreen;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerScreenBehaviorTest {
    @BeforeEach
    void clearPreviewBridgeTestState() {
        RoadPlannerGhostPreviewBridge.clearLastPreviewRequestForTest();
    }

    @Test
    void screenIsVanillaAndHandlesEscapeLayers() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);

        assertFalse(WindowScreen.class.isAssignableFrom(screen.getClass()));
        assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_CONTEXT_MENU, screen.handleEscapeForTest(true, false));
        assertEquals(RoadPlannerScreen.EscapeResult.CLOSE_SCREEN, screen.handleEscapeForTest(false, false));
        assertEquals(5, screen.actionCountForTest());
        assertTrue(screen.mapLayoutForTest().map().width() > screen.layoutForTest().map().width());
    }

    @Test
    void roadToolDrawsNodesOnMapCanvasAfterSelectingRoadTool() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        screen.mouseReleased(map.x() + 180, map.y() + 120, 0);

        assertTrue(screen.plannedNodeCountForTest() >= 2);
    }

    @Test
    void autoCompleteButtonGeneratesTownToTownNodes() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, "自动补全");

        assertTrue(screen.plannedNodeCountForTest() >= 2);
    }

    @Test
    void routeToolbarIncludesMergeCandidateActionsBetweenAutoCompleteAndConfirmBuild() {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.ROUTE);
        List<String> routeActions = toolbar.items().stream()
                .filter(item -> item.kind() == RoadPlannerTopToolbar.Kind.ACTION)
                .map(RoadPlannerTopToolbar.Item::label)
                .toList();

        assertEquals(List.of(
                RoadPlannerTopToolbar.ACTION_AUTO_COMPLETE,
                RoadPlannerTopToolbar.ACTION_MERGE_SCOPE,
                "\u786e\u8ba4\u5e76\u5165",
                RoadPlannerTopToolbar.ACTION_CONFIRM_BUILD,
                RoadPlannerTopToolbar.ACTION_CANCEL
        ), routeActions);
        assertTrue(toolbar.bounds().height() >= 36 + 5 * 24);
    }

    @Test
    void editToolbarContainsMergeToolAndToolsToolbarDoesNot() {
        RoadPlannerTopToolbar editToolbar = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.EDIT);
        RoadPlannerTopToolbar toolsToolbar = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS);

        assertTrue(editToolbar.items().stream().anyMatch(item ->
                item.kind() == RoadPlannerTopToolbar.Kind.TOOL && item.toolType() == RoadToolType.MERGE));
        assertTrue(toolsToolbar.items().stream().noneMatch(item -> item.toolType() == RoadToolType.MERGE));
        assertTrue(editToolbar.bounds().height() >= 36 + 3 * 24);
    }

    @Test
    void initSendsEntryPreloadAndZoomDoesNotCreateAnotherRequest() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, new BlockPos(0, 64, 0), new BlockPos(256, 64, 0));
        screen.init();

        RoadPlannerMapPreloadRequestPacket first = screen.lastMapPreloadRequestForTest();
        assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.ENTER_PLANNER_PRELOAD, first.purpose());

        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        screen.mouseScrolled(map.x() + 50, map.y() + 50, 1.0D);

        assertSame(first, screen.lastMapPreloadRequestForTest());
    }

    @Test
    void autoCompleteResultTriggersRoutePreloadRequest() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.applyAutoCompleteResult(screen.state().sessionId(), true,
                List.of(new BlockPos(0, 64, 0), new BlockPos(256, 64, 0)),
                List.of(RoadPlannerSegmentType.ROAD),
                "done");

        assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.ROUTE_PRELOAD, screen.lastMapPreloadRequestForTest().purpose());
    }

    @Test
    void autoCompleteResultRequestsMergeCandidatesForLastRoadNode() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        BlockPos end = new BlockPos(32, 64, 0);

        screen.applyAutoCompleteResult(screen.state().sessionId(), true,
                List.of(BlockPos.ZERO, end),
                List.of(RoadPlannerSegmentType.ROAD),
                "done");

        assertEquals(screen.state().sessionId(), screen.lastMergeCandidateRequestForTest().sessionId());
        assertEquals(end, screen.lastMergeCandidateRequestForTest().probe());
        assertEquals(RoadPlannerMergeScope.OWN_NATION, screen.lastMergeCandidateRequestForTest().scope());
        assertEquals(RoadPlannerSegmentType.ROAD, screen.lastMergeCandidateRequestForTest().currentSegmentType());
        assertNotEquals(new UUID(0L, 0L), screen.lastMergeCandidateRequestForTest().requestId());
    }

    @Test
    void forceRenderSelectionTriggersForceRenderPreloadRequest() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.FORCE_RENDER);
        screen.mouseClicked(map.x() + 40, map.y() + 40, 0);
        screen.mouseDragged(map.x() + 120, map.y() + 120, 0, 80, 80);
        screen.mouseReleased(map.x() + 120, map.y() + 120, 0);

        assertEquals(RoadPlannerMapPreloadRequestPacket.Purpose.FORCE_RENDER, screen.lastMapPreloadRequestForTest().purpose());
    }

    @Test
    void preloadProgressUpdatesStatusWhenRequestMatches() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, new BlockPos(0, 64, 0), new BlockPos(256, 64, 0));
        screen.init();
        RoadPlannerMapPreloadRequestPacket request = screen.lastMapPreloadRequestForTest();

        screen.applyMapPreloadProgress(new RoadPlannerMapPreloadProgressPacket(
                request.sessionId(),
                request.requestId(),
                request.purpose(),
                request.worldId(),
                request.dimensionId(),
                com.monpai.sailboatmod.roadplanner.map.RoadMapRoutePreloadPlan.CoverageMode.RECTANGLE,
                1,
                4,
                RoadPlannerMapPreloadProgressPacket.State.SAMPLING,
                "sampling"));

        assertTrue(screen.mapStatusLineForTest().contains("sampling"));
    }

    @Test
    void builtRoadRefreshTileSyncAcceptsMatchingWorldEvenWithDifferentSession() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        String before = screen.mapStatusLineForTest();

        screen.applyMapTileSync(new RoadPlannerMapTileSyncPacket(
                UUID.randomUUID(),
                99L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "world_a",
                "minecraft:overworld",
                MapLod.LOD_1,
                0,
                0,
                1,
                1,
                new int[]{0xFF00AA00}));

        assertNotEquals(before, screen.mapStatusLineForTest());
    }

    @Test
    void builtRoadRefreshTileSyncIgnoresWrongWorldContext() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        String before = screen.mapStatusLineForTest();

        screen.applyMapTileSync(new RoadPlannerMapTileSyncPacket(
                screen.state().sessionId(),
                99L,
                RoadPlannerMapPreloadRequestPacket.Purpose.BUILT_ROAD_REFRESH,
                "world_b",
                "minecraft:the_nether",
                MapLod.LOD_1,
                0,
                0,
                1,
                1,
                new int[]{0xFF00AA00}));

        assertEquals(before, screen.mapStatusLineForTest());
    }

    @Test
    void screenUsesTownAnchorsFromSessionRoute() {
        BlockPos start = new BlockPos(32, 64, 48);
        BlockPos destination = new BlockPos(320, 70, -96);

        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, start, destination);

        assertEquals(start, screen.startTownPosForTest());
        assertEquals(destination, screen.destinationTownPosForTest());
        assertEquals(0, screen.plannedNodeCountForTest());
    }

    @Test
    void clearRemovesAllPlayerNodesWhenRouteIsLoaded() {
        BlockPos start = new BlockPos(32, 64, 48);
        BlockPos destination = new BlockPos(320, 70, -96);
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, start, destination);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.EDIT, "清除");

        assertEquals(0, screen.plannedNodeCountForTest());
        assertEquals(start, screen.startTownPosForTest());
    }

    @Test
    void bezierFirstNodeOutsideStartClaimIsRejected() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(160, 64, 0);
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, start, destination, List.of(
                new RoadPlannerClaimOverlay(0, 0, "start", "Start", "", "", RoadPlannerClaimOverlay.Role.START, 0, 0),
                new RoadPlannerClaimOverlay(10, 0, "end", "End", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0, 0)
        ));
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.BEZIER);
        screen.mouseClicked(map.x() + map.width() - 20, map.y() + map.height() - 20, 0);

        assertEquals(0, screen.plannedNodeCountForTest());
    }

    @Test
    void endpointToolPlacesDestinationMarkerWithoutAutoLinkingEndpoints() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(160, 64, 0);
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, start, destination, List.of(
                new RoadPlannerClaimOverlay(0, 0, "start", "Start", "", "", RoadPlannerClaimOverlay.Role.START, 0, 0),
                new RoadPlannerClaimOverlay(10, 0, "end", "End", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0, 0)
        ));

        clickToolbarTool(screen, RoadToolType.ENDPOINT);
        assertTrue(screen.clickWorldForTest(4, 4));
        assertEquals(1, screen.plannedNodeCountForTest());
        assertTrue(screen.clickWorldForTest(164, 4));
        assertEquals(1, screen.plannedNodeCountForTest());
        assertEquals(new BlockPos(164, 64, 4), screen.destinationTownPosForTest());
    }

    @Test
    void endpointToolRejectsDestinationBeforeStart() {
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos destination = new BlockPos(160, 64, 0);
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720, start, destination, List.of(
                new RoadPlannerClaimOverlay(0, 0, "start", "Start", "", "", RoadPlannerClaimOverlay.Role.START, 0, 0),
                new RoadPlannerClaimOverlay(10, 0, "end", "End", "", "", RoadPlannerClaimOverlay.Role.DESTINATION, 0, 0)
        ));

        clickToolbarTool(screen, RoadToolType.ENDPOINT);
        assertTrue(screen.clickWorldForTest(164, 4));

        assertEquals(0, screen.plannedNodeCountForTest());
    }

    @Test
    void confirmMergeActionAppendsSelectedCandidateAnchorNode() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        List<OpenRoadMergeCandidatesPacket.Entry> candidates = List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2),
                mergeCandidate("road_b", new BlockPos(16, 64, 0), 5)
        );

        applyLatestMergeCandidates(screen, candidates);

        assertEquals(new RoadPlannerMergeSelection("road_a", 2, new BlockPos(8, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                screen.selectedMergeSelectionForTest());
        assertEquals(1, screen.plannedNodeCountForTest());

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_NEXT_MERGE);

        assertEquals(2, screen.plannedNodeCountForTest());
        assertEquals(new BlockPos(8, 64, 0), screen.plannedNodeForTest(1));
        assertEquals(new RoadPlannerMergeSelection("road_a", 2, new BlockPos(8, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                screen.selectedMergeSelectionForTest());
    }

    @Test
    void staleMergeCandidatePacketIsIgnored() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);

        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));
        screen.applyRoadMergeCandidates(UUID.randomUUID(), List.of(
                mergeCandidate("road_b", new BlockPos(16, 64, 0), 5)));

        assertEquals(new RoadPlannerMergeSelection("road_a", 2, new BlockPos(8, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                screen.selectedMergeSelectionForTest());
    }

    @Test
    void roadOverlayPacketReplacesVisibleOverlayStateForMatchingSession() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("own_road", new BlockPos(8, 64, 0), 2)));

        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("own_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0)),
                roadOverlay("trade_road", RoadPlannerMergeRelationship.TRADE, new BlockPos(32, 64, 0), new BlockPos(48, 64, 0))));
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("allied_road", RoadPlannerMergeRelationship.ALLIED, new BlockPos(64, 64, 0), new BlockPos(80, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertEquals(1, screen.roadOverlayCountForTest());
        assertEquals(1, states.size());
        assertEquals("allied_road", states.get(0).roadId());
        assertEquals(RoadPlannerMergeRelationship.ALLIED, states.get(0).relationship());
        assertEquals(0xCCB18CFF, states.get(0).color());
        assertFalse(states.get(0).selectedMergeAnchor());
    }

    @Test
    void staleRoadOverlayPacketIsIgnored() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("own_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        screen.applyRoadOverlays(UUID.randomUUID(), List.of(
                roadOverlay("trade_road", RoadPlannerMergeRelationship.TRADE, new BlockPos(32, 64, 0), new BlockPos(48, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertEquals(1, screen.roadOverlayCountForTest());
        assertEquals("own_road", states.get(0).roadId());
        assertEquals(0xCC8BD3FF, states.get(0).color());
    }

    @Test
    void sameSessionRoadOverlayResponseForSupersededViewportIsIgnored() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        screen.init();
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        RoadPlannerRoadOverlayRequestPacket firstRequest = screen.lastRoadOverlayRequestForTest();

        screen.mouseClicked(map.x() + 220, map.y() + 220, 2);
        screen.mouseDragged(map.x() + 320, map.y() + 260, 2, 100, 40);
        screen.mouseReleased(map.x() + 320, map.y() + 260, 2);
        RoadPlannerRoadOverlayRequestPacket secondRequest = screen.lastRoadOverlayRequestForTest();

        screen.applyRoadOverlays(
                screen.state().sessionId(),
                firstRequest.regionCenter(),
                firstRequest.regionSize(),
                firstRequest.scope(),
                List.of(roadOverlay("stale_road", RoadPlannerMergeRelationship.TRADE, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        assertEquals(0, screen.roadOverlayCountForTest());

        screen.applyRoadOverlays(
                screen.state().sessionId(),
                secondRequest.regionCenter(),
                secondRequest.regionSize(),
                secondRequest.scope(),
                List.of(roadOverlay("latest_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertEquals(1, states.size());
        assertEquals("latest_road", states.get(0).roadId());
    }

    @Test
    void sameSessionRoadOverlayResponseForSupersededMergeScopeIsIgnored() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        screen.init();
        RoadPlannerRoadOverlayRequestPacket firstRequest = screen.lastRoadOverlayRequestForTest();

        screen.applyRoadOverlays(
                screen.state().sessionId(),
                firstRequest.regionCenter(),
                firstRequest.regionSize(),
                firstRequest.scope(),
                List.of(roadOverlay("current_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);
        RoadPlannerRoadOverlayRequestPacket scopeRequest = screen.lastRoadOverlayRequestForTest();
        assertEquals(RoadPlannerMergeScope.ALLIED_OR_TRADE, scopeRequest.scope());
        assertEquals(firstRequest.regionCenter(), scopeRequest.regionCenter());
        assertEquals(firstRequest.regionSize(), scopeRequest.regionSize());

        screen.applyRoadOverlays(
                screen.state().sessionId(),
                firstRequest.regionCenter(),
                firstRequest.regionSize(),
                firstRequest.scope(),
                List.of(roadOverlay("stale_scope_road", RoadPlannerMergeRelationship.TRADE, new BlockPos(32, 64, 0), new BlockPos(48, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertEquals(1, screen.roadOverlayCountForTest());
        assertEquals(1, states.size());
        assertEquals("current_road", states.get(0).roadId());
        assertEquals(RoadPlannerMergeRelationship.OWN, states.get(0).relationship());
    }

    @Test
    void selectedMergeAnchorIsReportedForRoadOverlayRendering() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("own_road", new BlockPos(8, 64, 0), 2),
                mergeCandidate("trade_road", new BlockPos(48, 64, 0), 5)));

        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("own_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0)),
                roadOverlay("trade_road", RoadPlannerMergeRelationship.TRADE, new BlockPos(32, 64, 0), new BlockPos(48, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertTrue(states.stream().anyMatch(state ->
                state.roadId().equals("own_road")
                        && state.color() == 0xCC8BD3FF
                        && state.selectedMergeAnchor()));
        assertTrue(states.stream().anyMatch(state ->
                state.roadId().equals("trade_road")
                        && state.color() == 0xCCB18CFF
                        && !state.selectedMergeAnchor()));
    }

    @Test
    void roadOverlayRenderStateIncludesPathNodeMarkers() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);

        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay(
                        "own_road",
                        RoadPlannerMergeRelationship.OWN,
                        new BlockPos(0, 64, 0),
                        new BlockPos(8, 64, 0),
                        new BlockPos(16, 64, 0))));

        List<RoadPlannerScreen.RoadOverlayRenderStateForTest> states = screen.roadOverlayRenderStateForTest();
        assertEquals(1, states.size());
        assertEquals(3, states.get(0).nodeCount());
    }

    @Test
    void mergeToolClickingOverlaySelectsNearestExistingRoadNode() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                new RoadPlannerRoadOverlaySyncPacket.Entry(
                        "road-a",
                        RoadPlannerMergeRelationship.OWN,
                        List.of(
                                new BlockPos(0, 64, 0),
                                new BlockPos(40, 64, 0),
                                new BlockPos(80, 64, 0)),
                        "Alpha - Beta",
                        80,
                        "Builder",
                        "uuid-a",
                        1234L,
                        false)));

        clickToolbarTool(screen, RoadToolType.MERGE);
        screen.mouseClicked(screenXFromWorld(map, 43), screenZFromWorld(map, 0), 0);

        assertEquals(new RoadPlannerMergeSelection("road-a", 1, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                screen.selectedMergeSelectionForTest());
        assertTrue(screen.statusLineForTest().contains("Alpha - Beta"));
        assertEquals(1, screen.plannedNodeCountForTest());

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_NEXT_MERGE);

        assertEquals(3, screen.plannedNodeCountForTest());
        assertEquals(new BlockPos(40, 64, 0), screen.plannedNodeForTest(1));
        assertEquals(new BlockPos(80, 64, 0), screen.plannedNodeForTest(2));
    }

    @Test
    void mergeConfirmationAppendsExistingRoadTailTowardDestinationButPreviewBuildsOnlyConnector() throws Exception {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(screenXFromWorld(map, 36), screenZFromWorld(map, 0), 0);
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                new RoadPlannerRoadOverlaySyncPacket.Entry(
                        "road-a",
                        RoadPlannerMergeRelationship.OWN,
                        List.of(
                                new BlockPos(0, 64, 0),
                                new BlockPos(40, 64, 0),
                                new BlockPos(80, 64, 0),
                                new BlockPos(120, 64, 0),
                                new BlockPos(160, 64, 0)),
                        "Alpha - Beta",
                        160,
                        "Builder",
                        "uuid-a",
                        1234L,
                        false)));

        clickToolbarTool(screen, RoadToolType.MERGE);
        screen.mouseClicked(screenXFromWorld(map, 43), screenZFromWorld(map, 0), 0);
        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_NEXT_MERGE);

        assertEquals(List.of(
                        new BlockPos(36, 64, 0),
                        new BlockPos(40, 64, 0),
                        new BlockPos(80, 64, 0),
                        new BlockPos(120, 64, 0),
                        new BlockPos(160, 64, 0)),
                screen.plannedNodesForTest());

        invokeSubmitPreview(screen);

        RoadPlannerPreviewRequestPacket packet = RoadPlannerGhostPreviewBridge.lastPreviewRequestForTest();
        assertEquals(List.of(new BlockPos(36, 64, 0), new BlockPos(40, 64, 0)), packet.nodes());
        assertEquals(new RoadPlannerMergeSelection("road-a", 1, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                packet.mergeSelection());
    }

    @Test
    void mergeToolDoesNotSelectOverlayWhenCurrentSegmentIsBridge() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(screenXFromWorld(map, 0), screenZFromWorld(map, 0), 0);
        screen.mouseClicked(screenXFromWorld(map, 16), screenZFromWorld(map, 0), 0);
        assertTrue(screen.rightClickMapForTest(8, 0, 300, 300));
        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE);
        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, screen.segmentTypeForTest(0));
        screen.keyPressed(256, 0, 0);
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("road-a", RoadPlannerMergeRelationship.OWN, new BlockPos(40, 64, 0), new BlockPos(80, 64, 0))));

        clickToolbarTool(screen, RoadToolType.MERGE);
        screen.mouseClicked(screenXFromWorld(map, 40), screenZFromWorld(map, 0), 0);

        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());
        assertTrue(screen.statusLineForTest().contains("\u6865\u6881"));
    }

    @Test
    void roadOverlayTooltipReportsHoveredBuiltRoadMetadata() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                new RoadPlannerRoadOverlaySyncPacket.Entry(
                        "road-tooltip",
                        RoadPlannerMergeRelationship.OWN,
                        List.of(new BlockPos(0, 64, 0), new BlockPos(8, 64, 0)),
                        "Alpha - Beta",
                        8,
                        "Builder",
                        "uuid-a",
                        1234L,
                        false)));

        RoadPlannerScreen.RoadOverlayTooltipForTest tooltip = screen.roadOverlayTooltipForTest(
                map.x() + map.width() / 2 + 8,
                map.y() + map.height() / 2 + 2);

        assertEquals("road-tooltip", tooltip.roadId());
        assertEquals("Alpha - Beta", tooltip.displayName());
        assertTrue(tooltip.lines().contains("Length: 8 blocks"));
        assertTrue(tooltip.lines().contains("Creator: Builder"));
    }

    @Test
    void delayedSameSessionMergeCandidateResponseIsIgnoredAfterNewRequest() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        UUID firstRequestId = screen.lastMergeCandidateRequestForTest().requestId();

        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        UUID secondRequestId = screen.lastMergeCandidateRequestForTest().requestId();
        screen.applyRoadMergeCandidates(screen.state().sessionId(), firstRequestId, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));

        assertEquals(0, screen.mergeCandidateCountForTest());
        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());

        screen.applyRoadMergeCandidates(screen.state().sessionId(), secondRequestId, List.of(
                mergeCandidate("road_b", new BlockPos(16, 64, 0), 5)));

        assertEquals(new RoadPlannerMergeSelection("road_b", 5, new BlockPos(16, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                screen.selectedMergeSelectionForTest());
    }

    @Test
    void routeMutationClearsCandidatesAndRefreshesPendingMergeRequest() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        UUID firstRequestId = screen.lastMergeCandidateRequestForTest().requestId();
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));

        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);

        assertEquals(0, screen.mergeCandidateCountForTest());
        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());
        assertNotEquals(firstRequestId, screen.lastMergeCandidateRequestForTest().requestId());
    }

    @Test
    void enabledMergeScopeChangeClearsCandidatesAndRefetchesCurrentEndpoint() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        UUID firstRequestId = screen.lastMergeCandidateRequestForTest().requestId();
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);

        assertEquals(RoadPlannerMergeScope.ALLIED_OR_TRADE, screen.mergeScopeForTest());
        assertEquals(0, screen.mergeCandidateCountForTest());
        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());
        assertNotEquals(firstRequestId, screen.lastMergeCandidateRequestForTest().requestId());
        assertEquals(RoadPlannerMergeScope.ALLIED_OR_TRADE, screen.lastMergeCandidateRequestForTest().scope());
    }

    @Test
    void disabledMergeScopeClearsCandidatesAndSuppressesRequests() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);
        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);

        assertEquals(RoadPlannerMergeScope.DISABLED, screen.mergeScopeForTest());
        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());
        assertEquals(0, screen.mergeCandidateCountForTest());
        assertNull(screen.lastMergeCandidateRequestForTest());
    }

    @Test
    void disabledMergeScopeKeepsOwnRoadOverlaysVisibleWithoutSnapSelection() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        screen.init();

        screen.applyRoadOverlays(screen.state().sessionId(), List.of(
                roadOverlay("own_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);
        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);

        assertEquals(RoadPlannerMergeScope.DISABLED, screen.mergeScopeForTest());
        assertEquals(RoadPlannerMergeScope.OWN_NATION, screen.lastRoadOverlayRequestForTest().scope());

        screen.applyRoadOverlays(
                screen.state().sessionId(),
                screen.lastRoadOverlayRequestForTest().regionCenter(),
                screen.lastRoadOverlayRequestForTest().regionSize(),
                screen.lastRoadOverlayRequestForTest().scope(),
                List.of(roadOverlay("visible_own_road", RoadPlannerMergeRelationship.OWN, BlockPos.ZERO, new BlockPos(8, 64, 0))));

        assertEquals(RoadPlannerMergeSelection.none(), screen.selectedMergeSelectionForTest());
        assertEquals(1, screen.roadOverlayCountForTest());
        assertEquals("visible_own_road", screen.roadOverlayRenderStateForTest().get(0).roadId());
    }

    @Test
    void scopeChangeRequestsRoadOverlaysForCurrentViewport() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        screen.init();
        RoadPlannerRoadOverlayRequestPacket initialRequest = screen.lastRoadOverlayRequestForTest();

        clickToolbarAction(screen, RoadPlannerTopToolbar.Group.ROUTE, RoadPlannerTopToolbar.ACTION_MERGE_SCOPE);

        RoadPlannerRoadOverlayRequestPacket scopeRequest = screen.lastRoadOverlayRequestForTest();
        assertEquals(screen.state().sessionId(), scopeRequest.sessionId());
        assertEquals("world_a", scopeRequest.worldId());
        assertEquals("minecraft:overworld", scopeRequest.dimensionId());
        assertEquals(RoadPlannerMergeScope.ALLIED_OR_TRADE, scopeRequest.scope());
        assertEquals(initialRequest.regionCenter(), scopeRequest.regionCenter());
        assertEquals(initialRequest.regionSize(), scopeRequest.regionSize());
    }

    @Test
    void viewportPanRefreshesRoadOverlayRequestForNewCenter() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setTileManagerForTest(RoadPlannerTileManager.forTest(
                new File("roadplanner_tile_test"),
                "world_a",
                "minecraft:overworld"));
        screen.init();
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        RoadPlannerRoadOverlayRequestPacket initialRequest = screen.lastRoadOverlayRequestForTest();

        screen.mouseClicked(map.x() + 220, map.y() + 220, 2);
        screen.mouseDragged(map.x() + 320, map.y() + 260, 2, 100, 40);
        screen.mouseReleased(map.x() + 320, map.y() + 260, 2);

        RoadPlannerRoadOverlayRequestPacket viewportRequest = screen.lastRoadOverlayRequestForTest();
        assertTrue(viewportRequest != null);
        assertEquals(screen.state().sessionId(), viewportRequest.sessionId());
        assertEquals("world_a", viewportRequest.worldId());
        assertEquals("minecraft:overworld", viewportRequest.dimensionId());
        assertEquals(RoadPlannerMergeScope.OWN_NATION, viewportRequest.scope());
        assertNotEquals(initialRequest.regionCenter(), viewportRequest.regionCenter());
        assertEquals(initialRequest.regionSize(), viewportRequest.regionSize());
    }

    @Test
    void bridgeToolDoesNotRequestMergeCandidates() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.BRIDGE);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);

        assertNull(screen.lastMergeCandidateRequestForTest());
    }

    @Test
    void roadToolPlacementRequestsMergeCandidates() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        BlockPos placed = worldFromScreen(map, map.x() + 120, map.y() + 120);

        assertEquals(placed, screen.lastMergeCandidateRequestForTest().probe());
        assertEquals(RoadPlannerMergeScope.OWN_NATION, screen.lastMergeCandidateRequestForTest().scope());
    }


    @Test
    void forceRenderToolSelectionQueuesChunksWithoutPreviewLine() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();

        clickToolbarTool(screen, RoadToolType.FORCE_RENDER);
        screen.mouseClicked(map.x() + 40, map.y() + 40, 0);
        screen.mouseDragged(map.x() + 120, map.y() + 120, 0, 80, 80);
        screen.mouseReleased(map.x() + 120, map.y() + 120, 0);

        assertTrue(screen.forceRenderTotalChunksForTest() > 0);
    }

    @Test
    void rightClickPlannedNodeOpensPlannedRouteMenuBeforeGraphEdge() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        BlockPos plannedStart = worldFromScreen(map, map.x() + 120, map.y() + 120);
        BlockPos plannedEnd = worldFromScreen(map, map.x() + 180, map.y() + 120);

        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode from = graph.addNode(plannedStart, RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphNode to = graph.addNode(plannedEnd, RoadGraphNode.Kind.TOWN_CONNECTION);
        graph.addEdge(from.nodeId(), to.nodeId(), roadMetadata());
        screen.setGraphForTest(graph);

        assertTrue(screen.rightClickMapForTest(plannedStart.getX(), plannedStart.getZ(), 300, 300));

        assertEquals(RoadPlannerVanillaContextMenu.Kind.PLANNED_ROUTE, screen.contextMenuForTest().kind());
    }

    @Test
    void plannedRouteContextMenuPropertyActionUpdatesDraftSegment() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        BlockPos plannedStart = worldFromScreen(map, map.x() + 120, map.y() + 120);

        assertTrue(screen.rightClickMapForTest(plannedStart.getX(), plannedStart.getZ(), 300, 300));
        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE);

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, screen.segmentTypeForTest(0));
    }

    @Test
    void previewSubmissionKeepsEditableRouteNodesForLaterPropertyEdits() throws Exception {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        BlockPos plannedStart = worldFromScreen(map, map.x() + 120, map.y() + 120);

        int editableNodeCount = screen.plannedNodeCountForTest();
        invokeSubmitPreview(screen);

        assertEquals(editableNodeCount, screen.plannedNodeCountForTest(),
                "preview expansion must not replace the editable route with generated build samples");
        assertTrue(screen.rightClickMapForTest(plannedStart.getX(), plannedStart.getZ(), 300, 300));
        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE);

        assertEquals(RoadPlannerSegmentType.BRIDGE_MAJOR, screen.segmentTypeForTest(0));
    }

    @Test
    void previewSubmissionCarriesSelectedMergeSelection() throws Exception {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadPlannerMapLayout.Rect map = screen.mapLayoutForTest().map();
        clickToolbarTool(screen, RoadToolType.ROAD);
        screen.mouseClicked(map.x() + 120, map.y() + 120, 0);
        screen.mouseClicked(map.x() + 180, map.y() + 120, 0);
        applyLatestMergeCandidates(screen, List.of(
                mergeCandidate("road_a", new BlockPos(8, 64, 0), 2)));

        invokeSubmitPreview(screen);

        RoadPlannerPreviewRequestPacket packet = RoadPlannerGhostPreviewBridge.lastPreviewRequestForTest();
        assertEquals(new RoadPlannerMergeSelection("road_a", 2, new BlockPos(8, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                packet.mergeSelection());
    }


    @Test
    void contextMenuDemolishEdgeRemovesSelectedGraphEdge() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode from = graph.addNode(BlockPos.ZERO, RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphNode to = graph.addNode(new BlockPos(80, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphEdge edge = graph.addEdge(from.nodeId(), to.nodeId(), roadMetadata());
        screen.setGraphForTest(graph);
        assertTrue(screen.rightClickMapForTest(40, 0, 300, 300));

        screen.handleContextActionForTest(RoadPlannerContextMenuAction.DEMOLISH_EDGE);

        assertTrue(graph.edge(edge.edgeId()).isEmpty());
        assertTrue(screen.statusLineForTest().contains("\u62c6\u9664"));
    }

    @Test
    void contextMenuPropertyActionsSetSelectedEdgeTypeDirectly() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode from = graph.addNode(BlockPos.ZERO, RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphNode to = graph.addNode(new BlockPos(80, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphEdge edge = graph.addEdge(from.nodeId(), to.nodeId(), roadMetadata());
        screen.setGraphForTest(graph);
        assertTrue(screen.rightClickMapForTest(40, 0, 300, 300));

        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_BRIDGE_TYPE);
        assertEquals(CompiledRoadSectionType.BRIDGE, graph.edge(edge.edgeId()).orElseThrow().metadata().type());

        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_TUNNEL_TYPE);
        assertEquals(CompiledRoadSectionType.TUNNEL, graph.edge(edge.edgeId()).orElseThrow().metadata().type());

        screen.handleContextActionForTest(RoadPlannerContextMenuAction.SET_ROAD_TYPE);
        assertEquals(CompiledRoadSectionType.ROAD, graph.edge(edge.edgeId()).orElseThrow().metadata().type());
    }

    @Test
    void contextMenuConnectTownSwitchesToEndpointTool() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode from = graph.addNode(BlockPos.ZERO, RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphNode to = graph.addNode(new BlockPos(80, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
        graph.addEdge(from.nodeId(), to.nodeId(), roadMetadata());
        screen.setGraphForTest(graph);
        assertTrue(screen.rightClickMapForTest(40, 0, 300, 300));

        screen.handleContextActionForTest(RoadPlannerContextMenuAction.CONNECT_TOWN);

        assertEquals(RoadToolType.ENDPOINT, screen.activeToolForTest());
    }

    @Test
    void staleSnapshotDoesNotMarkMapUpdated() {
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        RoadMapSnapshotSyncPacket stale = new RoadMapSnapshotSyncPacket(
                screen.state().sessionId(),
                "world",
                "minecraft:overworld",
                999L,
                RoadMapSnapshotRequestPacket.Purpose.INITIAL_VIEWPORT,
                0,
                0,
                128,
                MapLod.LOD_4,
                32,
                32,
                new int[32 * 32]);

        screen.applyMapSnapshot(stale);

        assertTrue(screen.mapStatusLineForTest().contains("等待地图"));
    }

    private RoadRouteMetadata roadMetadata() {
        return new RoadRouteMetadata(
                "\u6d4b\u8bd5\u9053\u8def",
                "A",
                "B",
                UUID.randomUUID(),
                0L,
                5,
                CompiledRoadSectionType.ROAD,
                RoadRouteMetadata.Status.BUILT
        );
    }

    private OpenRoadMergeCandidatesPacket.Entry mergeCandidate(String roadId, BlockPos anchorPos, int pathIndex) {
        return new OpenRoadMergeCandidatesPacket.Entry(
                roadId,
                anchorPos,
                pathIndex,
                Math.max(0, (int) Math.round(Math.sqrt(anchorPos.distSqr(BlockPos.ZERO)))),
                "Alpha",
                "Beta",
                "nation-a",
                RoadPlannerMergeRelationship.OWN
        );
    }

    private RoadPlannerRoadOverlaySyncPacket.Entry roadOverlay(String roadId,
                                                              RoadPlannerMergeRelationship relationship,
                                                              BlockPos... path) {
        return new RoadPlannerRoadOverlaySyncPacket.Entry(roadId, relationship, List.of(path));
    }

    private void applyLatestMergeCandidates(RoadPlannerScreen screen, List<OpenRoadMergeCandidatesPacket.Entry> candidates) {
        screen.applyRoadMergeCandidates(
                screen.state().sessionId(),
                screen.lastMergeCandidateRequestForTest().requestId(),
                candidates);
    }

    private void clickToolbarTool(RoadPlannerScreen screen, RoadToolType toolType) {
        RoadPlannerTopToolbar.Group toolbarGroup = toolType == RoadToolType.MERGE
                ? RoadPlannerTopToolbar.Group.EDIT
                : RoadPlannerTopToolbar.Group.TOOLS;
        int groupIndex = toolbarGroup == RoadPlannerTopToolbar.Group.EDIT ? 1 : 0;
        RoadPlannerTopToolbar.Item group = RoadPlannerTopToolbar.defaultToolbar(1280).items().get(groupIndex);
        screen.mouseClicked(group.bounds().x() + 4, group.bounds().y() + 4, 0);
        RoadPlannerTopToolbar.Item item = RoadPlannerTopToolbar.toolbar(1280, toolbarGroup)
                .items().stream().filter(candidate -> candidate.toolType() == toolType).findFirst().orElseThrow();
        screen.mouseClicked(item.bounds().x() + 4, item.bounds().y() + 4, 0);
    }

    private void clickToolbarAction(RoadPlannerScreen screen, RoadPlannerTopToolbar.Group group, String label) {
        int groupIndex = group == RoadPlannerTopToolbar.Group.EDIT ? 1 : 2;
        RoadPlannerTopToolbar.Item groupItem = RoadPlannerTopToolbar.defaultToolbar(1280).items().get(groupIndex);
        screen.mouseClicked(groupItem.bounds().x() + 4, groupItem.bounds().y() + 4, 0);
        RoadPlannerTopToolbar.Item action = RoadPlannerTopToolbar.toolbar(1280, group)
                .items().stream().filter(item -> label.equals(item.label())).findFirst().orElseThrow();
        screen.mouseClicked(action.bounds().x() + 4, action.bounds().y() + 4, 0);
    }

    private BlockPos worldFromScreen(RoadPlannerMapLayout.Rect map, int screenX, int screenY) {
        int worldX = (int) Math.round((screenX - (map.x() + map.width() / 2.0D)) / 2.0D);
        int worldZ = (int) Math.round((screenY - (map.y() + map.height() / 2.0D)) / 2.0D);
        return new BlockPos(worldX, 64, worldZ);
    }

    private int screenXFromWorld(RoadPlannerMapLayout.Rect map, int worldX) {
        return (int) Math.round(map.x() + map.width() / 2.0D + worldX * 2.0D);
    }

    private int screenZFromWorld(RoadPlannerMapLayout.Rect map, int worldZ) {
        return (int) Math.round(map.y() + map.height() / 2.0D + worldZ * 2.0D);
    }

    private void invokeSubmitPreview(RoadPlannerScreen screen) throws Exception {
        Method method = RoadPlannerScreen.class.getDeclaredMethod("submitPreviewWithSettings", RoadPlannerBuildSettings.class);
        method.setAccessible(true);
        method.invoke(screen, RoadPlannerBuildSettings.DEFAULTS);
    }
}
