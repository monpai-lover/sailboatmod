package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import gg.essential.elementa.WindowScreen;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerScreenBehaviorTest {
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
    void endpointToolSetsStartOrEndByTownClaimRole() {
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
        assertEquals(2, screen.plannedNodeCountForTest());
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

    private void clickToolbarTool(RoadPlannerScreen screen, RoadToolType toolType) {
        RoadPlannerTopToolbar.Item group = RoadPlannerTopToolbar.defaultToolbar(1280).items().get(0);
        screen.mouseClicked(group.bounds().x() + 4, group.bounds().y() + 4, 0);
        RoadPlannerTopToolbar.Item item = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS)
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
}
