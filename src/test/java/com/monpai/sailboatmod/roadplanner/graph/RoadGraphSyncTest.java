package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.nation.model.RoadNetworkRecord;
import com.monpai.sailboatmod.route.RoadGraphRoutingService;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphSyncTest {
    private static final String DIM = "minecraft:overworld";

    @Test
    void syncRoadCreatesTwoEndpointNodesAndOneBuiltEdge() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        RoadNetworkRecord road = road("alpha", straight(new BlockPos(0, 64, 0), new BlockPos(20, 64, 0)));

        assertTrue(RoadGraphSync.syncRoad(graph, road));

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
        RoadGraphEdgeRecord edge = graph.edges().iterator().next();
        assertTrue(edge.built());
        assertEquals(road.path(), edge.centerline());
    }

    @Test
    void syncRoadIsIdempotentAcrossRepeatedCalls() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        RoadNetworkRecord road = road("alpha", straight(new BlockPos(0, 64, 0), new BlockPos(20, 64, 0)));

        assertTrue(RoadGraphSync.syncRoad(graph, road));
        // 第二次同步同一条道路：确定性 id 让其覆盖既有节点/边，不应让图膨胀，且因已同步而返回 false。
        assertFalse(RoadGraphSync.syncRoad(graph, road));

        assertEquals(2, graph.nodes().size());
        assertEquals(1, graph.edges().size());
    }

    @Test
    void roadBecomesRoutableAfterSync() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        BlockPos start = new BlockPos(0, 64, 0);
        BlockPos end = new BlockPos(20, 64, 0);
        RoadNetworkRecord road = road("alpha", straight(start, end));

        RoadGraphRoutingService before =
                new RoadGraphRoutingService(new RoadGraphRepository(graph));
        assertTrue(before.route(DIM, start, end, 12).size() < 2, "同步前应当无法路由");

        RoadGraphSync.syncRoad(graph, road);

        RoadGraphRoutingService after =
                new RoadGraphRoutingService(new RoadGraphRepository(graph));
        List<BlockPos> path = after.route(DIM, start, end, 12);
        assertEquals(start, path.get(0));
        assertEquals(end, path.get(path.size() - 1));
    }

    @Test
    void syncRoadRejectsBlankOrTooShortRoads() {
        RoadNetworkGraphSavedData graph = new RoadNetworkGraphSavedData();
        assertFalse(RoadGraphSync.syncRoad(graph, road("", straight(new BlockPos(0, 64, 0), new BlockPos(20, 64, 0)))));
        assertFalse(RoadGraphSync.syncRoad(graph, road("alpha", List.of(new BlockPos(0, 64, 0)))));
        assertEquals(0, graph.edges().size());
    }

    private static RoadNetworkRecord road(String roadId, List<BlockPos> path) {
        return new RoadNetworkRecord(
                roadId,
                "alpha",
                "town",
                DIM,
                "a",
                "b",
                path,
                1L,
                RoadNetworkRecord.SOURCE_TYPE_MANUAL);
    }

    private static List<BlockPos> straight(BlockPos from, BlockPos to) {
        List<BlockPos> points = new ArrayList<>();
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        BlockPos cursor = from;
        points.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, 0, 0);
            points.add(cursor);
        }
        return List.copyOf(points);
    }
}
