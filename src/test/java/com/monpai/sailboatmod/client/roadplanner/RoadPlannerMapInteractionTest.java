package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapInteractionTest {
    @Test
    void rightClickNearRoadLineSelectsEdgeAndOpensRoadweaverMenu() {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode a = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphNode b = graph.addNode(new BlockPos(64, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphEdge edge = graph.addEdge(a.nodeId(), b.nodeId(), metadata());
        RoadPlannerClientState state = RoadPlannerClientState.open(UUID.randomUUID());

        RoadPlannerMapInteractionResult result = new RoadPlannerMapInteraction(graph).rightClickRoadLine(state, 30, 3, 140, 90, 8);

        assertEquals(edge.edgeId(), result.state().selectedRoadEdgeId());
        assertEquals(edge.edgeId(), result.contextMenu().orElseThrow().roadEdgeId());
        assertTrue(result.contextMenu().orElseThrow().bounds().contains(140, 90));
    }

    @Test
    void contextMenuClickCreatesRenameTextInputIntent() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        RoadWeaverStyleContextMenuModel menu = RoadWeaverStyleContextMenuModel.forRoadEdge(edgeId, 100, 50);

        RoadPlannerMapInteractionResult result = RoadPlannerMapInteraction.handleMenuClick(routeId, "港口大道", menu, 108, 63, 0);

        assertEquals(RoadPlannerContextMenuAction.RENAME_ROAD, result.action().orElseThrow());
        assertEquals("港口大道", result.textInput().orElseThrow().initialValue());
    }

    @Test
    void vanillaScreenRightClickMapOpensContextMenuForGraphEdge() {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode a = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphNode b = graph.addNode(new BlockPos(64, 64, 0), RoadGraphNode.Kind.NORMAL);
        graph.addEdge(a.nodeId(), b.nodeId(), metadata());
        RoadPlannerScreen screen = RoadPlannerScreen.forTest(UUID.randomUUID(), 1280, 720);
        screen.setGraphForTest(graph);

        boolean consumed = screen.rightClickMapForTest(30, 2, 360, 240);

        assertTrue(consumed);
        assertTrue(screen.contextMenuForTest().isOpen());
    }

    private RoadRouteMetadata metadata() {
        return new RoadRouteMetadata("港口大道", "主城", "港口镇", UUID.randomUUID(), 0L, 5,
                CompiledRoadSectionType.ROAD, RoadRouteMetadata.Status.BUILT);
    }
}
