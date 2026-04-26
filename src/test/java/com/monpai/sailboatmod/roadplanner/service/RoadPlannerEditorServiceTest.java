package com.monpai.sailboatmod.roadplanner.service;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerDemolishRoadPacket;
import com.monpai.sailboatmod.roadplanner.build.RoadRollbackLedger;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdge;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNode;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraph;
import com.monpai.sailboatmod.roadplanner.graph.RoadRouteMetadata;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerEditorServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void renameRoadUpdatesGraphMetadata() {
        RoadNetworkGraph graph = new RoadNetworkGraph();
        RoadGraphEdge edge = sampleEdge(graph, "旧名");

        RoadPlannerEditorService.RenameResult result = new RoadPlannerEditorService().renameRoad(graph, edge.edgeId(), "新名");

        assertTrue(result.success());
        assertEquals("新名", result.edge().orElseThrow().metadata().roadName());
    }

    @Test
    void missingEdgeReturnsBlockingIssue() {
        RoadPlannerEditorService.RenameResult result = new RoadPlannerEditorService().renameRoad(new RoadNetworkGraph(), UUID.randomUUID(), "x");

        assertTrue(result.issues().get(0).blocking());
    }

    @Test
    void demolishPlanningCreatesJobFromLedger() {
        UUID routeId = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        RoadRollbackLedger ledger = new RoadRollbackLedger();
        ledger.record(routeId, edgeId, UUID.randomUUID(), UUID.randomUUID(), 0L, new BlockPos(0, 64, 0), Blocks.GRASS_BLOCK.defaultBlockState(), null);

        RoadPlannerEditorService.DemolishResult result = new RoadPlannerEditorService()
                .planDemolition(routeId, edgeId, RoadPlannerDemolishRoadPacket.Scope.EDGE, ledger, Map.of());

        assertTrue(result.job().isPresent());
    }

    private RoadGraphEdge sampleEdge(RoadNetworkGraph graph, String roadName) {
        RoadGraphNode a = graph.addNode(BlockPos.ZERO, RoadGraphNode.Kind.NORMAL);
        RoadGraphNode b = graph.addNode(new BlockPos(16, 64, 0), RoadGraphNode.Kind.NORMAL);
        return graph.addEdge(a.nodeId(), b.nodeId(), new RoadRouteMetadata(roadName, "主城", "港口", UUID.randomUUID(), 0L, 5,
                CompiledRoadSectionType.ROAD, RoadRouteMetadata.Status.BUILT));
    }
}
