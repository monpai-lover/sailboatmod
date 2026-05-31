package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphOverlayServiceTest {
    @Test
    void returnsGraphRoadsAndWeakLegacyRoadsButOnlyGraphCandidates() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        graph.putNode(a);
        graph.putNode(b);
        RoadGraphEdgeRecord edge = edge(a.nodeId(), b.nodeId());
        graph.putEdge(edge);

        NationSavedData legacyData = new NationSavedData();
        legacyData.putRoadNetwork(new RoadNetworkRecord("legacy-road", "alpha", "", "minecraft:overworld",
                "a", "b", List.of(new BlockPos(0, 64, 10), new BlockPos(10, 64, 10)),
                1L, RoadNetworkRecord.SOURCE_TYPE_MANUAL));

        RoadGraphOverlayService service = new RoadGraphOverlayService(new RoadGraphRepository(graph), legacyData);

        List<RoadGraphOverlayService.Overlay> overlays = service.visibleOverlays(
                "minecraft:overworld", new BlockPos(5, 64, 5), 64, 4);
        List<RoadGraphOverlayService.Candidate> candidates = service.candidatesNear(
                "minecraft:overworld", new BlockPos(5, 64, 0), 12);

        assertEquals(2, overlays.size());
        assertTrue(overlays.stream().anyMatch(RoadGraphOverlayService.Overlay::legacy));
        assertTrue(overlays.stream().anyMatch(overlay -> !overlay.legacy()));
        assertEquals(List.of(edge.edgeId()), candidates.stream().map(RoadGraphOverlayService.Candidate::edgeId).toList());
        assertFalse(candidates.stream().anyMatch(candidate -> candidate.displayName().equals("legacy-road")));
    }

    private static RoadGraphNodeRecord node(BlockPos pos) {
        return new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld", pos,
                RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
    }

    private static RoadGraphEdgeRecord edge(UUID from, UUID to) {
        List<BlockPos> path = List.of(new BlockPos(0, 64, 0), new BlockPos(10, 64, 0));
        return new RoadGraphEdgeRecord(UUID.randomUUID(), from, to, "minecraft:overworld", "alpha", "",
                "", "", "Town A", "Town B", "Graph Road", 3, CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT, path, path,
                path.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos))).toList(),
                List.of(), List.of(), 1L, 1L);
    }
}
