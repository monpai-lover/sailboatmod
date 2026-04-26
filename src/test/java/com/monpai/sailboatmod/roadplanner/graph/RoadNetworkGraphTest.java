package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.client.roadplanner.RoadTooltipModel;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadNetworkGraphTest {
    @Test
    void graphSupportsBranchesNamesTownConnectionsAndCreator() {
        UUID creator = UUID.randomUUID();
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode center = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.JUNCTION);
        RoadGraphNode townA = graph.addNode(new BlockPos(64, 64, 0), RoadGraphNode.Kind.TOWN_CONNECTION);
        RoadGraphNode townB = graph.addNode(new BlockPos(0, 64, 64), RoadGraphNode.Kind.TOWN_CONNECTION);

        RoadGraphEdge edgeA = graph.addEdge(center.nodeId(), townA.nodeId(), metadata("港口大道", "主城", "港口镇", creator));
        graph.addEdge(center.nodeId(), townB.nodeId(), metadata("林地支路", "主城", "林地镇", creator));

        assertEquals(2, graph.edgesConnectedTo(center.nodeId()).size());
        assertEquals("港口大道", edgeA.metadata().roadName());
        assertEquals(64, Math.round(edgeA.lengthBlocks()));
        assertEquals(creator, edgeA.metadata().creatorId());
    }

    @Test
    void hitTesterFindsNearestEdgeWithinThreshold() {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphNode a = graph.addNode(new BlockPos(0, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphNode b = graph.addNode(new BlockPos(64, 64, 0), RoadGraphNode.Kind.NORMAL);
        RoadGraphEdge edge = graph.addEdge(a.nodeId(), b.nodeId(), metadata("港口大道", "主城", "港口镇", UUID.randomUUID()));

        Optional<RoadGraphEdge> hit = new RoadGraphHitTester(graph).nearestEdge(30, 4, 8);

        assertEquals(edge.edgeId(), hit.orElseThrow().edgeId());
    }

    @Test
    void tooltipContainsRoadInfo() {
        UUID creator = UUID.randomUUID();
        RoadRouteMetadata metadata = metadata("港口大道", "主城", "港口镇", creator);
        RoadTooltipModel tooltip = RoadTooltipModel.from(metadata, 384, 5, CompiledRoadSectionType.BRIDGE, "已建造");

        List<String> lines = tooltip.lines();

        assertTrue(lines.contains("港口大道"));
        assertTrue(lines.stream().anyMatch(line -> line.contains("主城") && line.contains("港口镇")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("384")));
        assertTrue(lines.stream().anyMatch(line -> line.contains(creator.toString())));
        assertTrue(lines.stream().anyMatch(line -> line.contains("已建造")));
    }

    private RoadRouteMetadata metadata(String roadName, String fromTown, String toTown, UUID creator) {
        return new RoadRouteMetadata(roadName, fromTown, toTown, creator, 0L, 5, CompiledRoadSectionType.ROAD, RoadRouteMetadata.Status.BUILT);
    }
}
