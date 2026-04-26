package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import com.monpai.sailboatmod.roadplanner.map.MapLod;
import com.monpai.sailboatmod.roadplanner.map.RoadMapRegion;
import com.monpai.sailboatmod.roadplanner.map.RoadMapViewport;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapCanvasTest {
    @Test
    void canvasConvertsMouseToWorld() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(100, 60, 400, 300);
        RoadPlannerMapComponent component = component(rect);
        RoadPlannerMapCanvas canvas = new RoadPlannerMapCanvas(rect, component);

        BlockPos world = canvas.mouseToWorld(300, 210);

        assertTrue(canvas.contains(110, 70));
        assertEquals(0, world.getX());
        assertEquals(0, world.getZ());
    }

    @Test
    void canvasRightClickGraphOpensContextMenu() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(100, 60, 400, 300);
        RoadPlannerMapCanvas canvas = new RoadPlannerMapCanvas(rect, component(rect));
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode a = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphNode b = graph.addNode(new BlockPos(64, 64, 0), RoadGraphNode.Kind.NORMAL);
        graph.addEdge(a.nodeId(), b.nodeId(), new RoadRouteMetadata("港口大道", "主城", "港口", UUID.randomUUID(), 0L, 5,
                CompiledRoadSectionType.ROAD, RoadRouteMetadata.Status.BUILT));

        RoadPlannerMapInteractionResult result = canvas.rightClickGraph(RoadPlannerClientState.open(UUID.randomUUID()), graph, 30, 2, 220, 160);

        assertTrue(result.contextMenu().isPresent());
    }

    private RoadPlannerMapComponent component(RoadPlannerVanillaLayout.Rect rect) {
        RoadMapRegion region = RoadMapRegion.centeredOn(BlockPos.ZERO, 128, MapLod.LOD_1);
        RoadMapViewport viewport = new RoadMapViewport(rect.x(), rect.y(), rect.width(), rect.height());
        return new RoadPlannerMapComponent(region, viewport);
    }
}
