package com.monpai.sailboatmod.roadplanner.edit;

import com.monpai.sailboatmod.nation.data.NationSavedData;
import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadEditCompletionRefreshServiceTest {
    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void refreshUpdatesPublicRoadAndGraphFromEditableRecord() {
        UUID edgeId = UUID.randomUUID();
        UUID fromNodeId = UUID.randomUUID();
        UUID toNodeId = UUID.randomUUID();
        NationSavedData nationData = new NationSavedData();
        nationData.putRoadNetwork(publicRoad(
                List.of(pos(0), pos(4), pos(8)),
                List.of(pos(0), pos(8))));
        RoadNetworkGraphSavedData graphData = graphWithEdge(edgeId, fromNodeId, toNodeId,
                List.of(pos(0), pos(4), pos(8)),
                List.of(pos(0), pos(8)),
                List.of(pos(4)));
        RoadEditableRecord target = editableRoad(edgeId.toString(),
                List.of(pos(16), pos(24), pos(32)),
                List.of(pos(16), pos(32)),
                List.of(pos(16), pos(24), pos(32)));
        RoadEditDiff diff = new RoadEditDiff(
                "road-a",
                List.of(new RoadEditDiff.RemovedBlock("road-a:segment:0", pos(4))),
                List.of(),
                List.of(new RoadEditBlockPlacement("road-a:segment:0", pos(24), Blocks.SMOOTH_STONE.defaultBlockState())),
                List.of());

        RoadEditCompletionRefreshService.RefreshResult result =
                RoadEditCompletionRefreshService.refreshForTest(nationData, graphData, target, diff, 500L);

        RoadNetworkRecord refreshedRoad = nationData.getRoadNetwork("road-a");
        assertEquals(List.of(pos(16), pos(24), pos(32)), refreshedRoad.path());
        assertEquals(List.of(pos(16), pos(32)), refreshedRoad.displayPath());
        assertEquals("nation-a", refreshedRoad.nationId());
        assertEquals("town-a", refreshedRoad.townId());
        assertEquals("creator-uuid", refreshedRoad.creatorUuid());
        assertEquals("Alpha", refreshedRoad.routeSourceName());
        assertEquals("Beta", refreshedRoad.routeTargetName());
        assertEquals("town-a", refreshedRoad.routeSourceTownId());
        assertEquals("town-b", refreshedRoad.routeTargetTownId());
        assertEquals(100L, refreshedRoad.createdAt());
        assertEquals(500L, refreshedRoad.updatedAt());
        assertTrue(refreshedRoad.sharedSpans().isEmpty());

        RoadGraphEdgeRecord refreshedEdge = graphData.getEdge(edgeId).orElseThrow();
        assertEquals(List.of(pos(16), pos(24), pos(32)), refreshedEdge.centerline());
        assertEquals(List.of(pos(16), pos(32)), refreshedEdge.displayPath());
        assertEquals(List.of(pos(16), pos(24), pos(32)), refreshedEdge.ownedBlockPositions());
        assertEquals(3, refreshedEdge.width());
        assertEquals(CompiledRoadSectionType.ROAD, refreshedEdge.sectionType());
        assertEquals("Old Road Name", refreshedEdge.roadName());
        assertEquals(1, refreshedEdge.placements().size());
        assertEquals(List.of(pos(16), pos(24), pos(32)), refreshedEdge.placements().get(0).positions());
        assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0), new ChunkPos(2, 0)), result.affectedChunks());
    }

    @Test
    void refreshCreatesPublicRecordWhenMissingButKeepsDeterministicGraphEdge() {
        UUID edgeId = RoadEditCompletionRefreshService.edgeIdForRoadIdForTest("road-a");
        UUID fromNodeId = UUID.randomUUID();
        UUID toNodeId = UUID.randomUUID();
        NationSavedData nationData = new NationSavedData();
        RoadNetworkGraphSavedData graphData = graphWithEdge(edgeId, fromNodeId, toNodeId,
                List.of(pos(0), pos(8)),
                List.of(pos(0), pos(8)),
                List.of(pos(0), pos(8)));
        RoadEditableRecord target = editableRoad("",
                List.of(pos(16), pos(32)),
                List.of(pos(16), pos(32)),
                List.of(pos(16), pos(32)));

        RoadEditCompletionRefreshService.refreshForTest(nationData, graphData, target,
                new RoadEditDiff("road-a", List.of(), List.of(), List.of(), List.of()), 500L);

        assertEquals(List.of(pos(16), pos(32)), nationData.getRoadNetwork("road-a").path());
        assertEquals(List.of(pos(16), pos(32)), graphData.getEdge(edgeId).orElseThrow().centerline());
    }

    private static RoadNetworkRecord publicRoad(List<BlockPos> path, List<BlockPos> displayPath) {
        return new RoadNetworkRecord(
                "road-a",
                "nation-a",
                "town-a",
                "minecraft:overworld",
                "planner:start:0,63,0",
                "planner:end:8,63,0",
                path,
                displayPath,
                List.of(new com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan(
                        "shared-road",
                        0,
                        1,
                        path.get(0),
                        path.get(path.size() - 1),
                        com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope.OWN_NATION,
                        com.monpai.sailboatmod.roadplanner.model.RoadPlannerSharedRoadSpan.Role.END_MERGE)),
                200L,
                100L,
                "creator-uuid",
                "Builder",
                RoadNetworkRecord.SOURCE_TYPE_MANUAL,
                "Alpha",
                "Beta",
                "town-a",
                "town-b");
    }

    private static RoadNetworkGraphSavedData graphWithEdge(UUID edgeId,
                                                           UUID fromNodeId,
                                                           UUID toNodeId,
                                                           List<BlockPos> centerline,
                                                           List<BlockPos> displayPath,
                                                           List<BlockPos> ownedBlocks) {
        RoadNetworkGraphSavedData graphData = new RoadNetworkGraphSavedData();
        graphData.putNode(new RoadGraphNodeRecord(fromNodeId, "minecraft:overworld", centerline.get(0),
                RoadGraphNodeRecord.Kind.NORMAL, "nation-a", "town-a", "", 100L, 200L));
        graphData.putNode(new RoadGraphNodeRecord(toNodeId, "minecraft:overworld", centerline.get(centerline.size() - 1),
                RoadGraphNodeRecord.Kind.NORMAL, "nation-a", "town-a", "", 100L, 200L));
        graphData.putEdge(new RoadGraphEdgeRecord(
                edgeId,
                fromNodeId,
                toNodeId,
                "minecraft:overworld",
                "nation-a",
                "town-a",
                "creator-uuid",
                "Builder",
                "Alpha",
                "Beta",
                "Old Road Name",
                5,
                CompiledRoadSectionType.BRIDGE,
                RoadGraphEdgeRecord.Status.BUILT,
                centerline,
                displayPath,
                List.of(),
                ownedBlocks,
                List.of(),
                100L,
                200L));
        return graphData;
    }

    private static RoadEditableRecord editableRoad(String edgeId,
                                                   List<BlockPos> centerline,
                                                   List<BlockPos> displayPath,
                                                   List<BlockPos> blockPositions) {
        return new RoadEditableRecord(
                "road-a",
                edgeId,
                "minecraft:overworld",
                "nation-a",
                "town-a",
                "creator-uuid",
                "Builder",
                "town-a",
                "town-b",
                "Alpha",
                "Beta",
                3,
                "minecraft:smooth_stone",
                RoadEditableRecord.Status.BUILT,
                false,
                List.of(
                        new RoadEditableNode("road-a:source", displayPath.get(0), RoadEditableNode.Kind.SOURCE_TOWN, "Alpha"),
                        new RoadEditableNode("road-a:target", displayPath.get(displayPath.size() - 1), RoadEditableNode.Kind.TARGET_TOWN, "Beta")),
                List.of(new RoadEditableSegment(
                        "road-a:segment:0",
                        "road-a:source",
                        "road-a:target",
                        centerline,
                        displayPath,
                        3,
                        "ROAD",
                        "minecraft:smooth_stone",
                        blockPositions.stream().map(BlockPos::asLong).toList())),
                100L,
                500L);
    }

    private static BlockPos pos(int x) {
        return new BlockPos(x, 63, 0);
    }
}
