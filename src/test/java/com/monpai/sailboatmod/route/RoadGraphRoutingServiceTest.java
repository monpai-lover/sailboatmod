package com.monpai.sailboatmod.route;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphRoutingServiceTest {
    @Test
    void resolvesShortestGraphPathAcrossBranch() {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        RoadGraphNodeRecord c = node(new BlockPos(20, 64, 0));
        data.putNode(a);
        data.putNode(b);
        data.putNode(c);
        data.putEdge(edge(a, b));
        data.putEdge(edge(b, c));
        RoadGraphRoutingService service = new RoadGraphRoutingService(new RoadGraphRepository(data));

        List<BlockPos> path = service.route("minecraft:overworld", a.pos(), c.pos(), 12);

        assertEquals(new BlockPos(0, 64, 0), path.get(0));
        assertEquals(new BlockPos(20, 64, 0), path.get(path.size() - 1));
        assertTrue(path.contains(new BlockPos(10, 64, 0)));
    }

    @Test
    void corridorMembershipUsesGraphFootprint() {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        RoadGraphNodeRecord a = node(new BlockPos(0, 64, 0));
        RoadGraphNodeRecord b = node(new BlockPos(10, 64, 0));
        data.putNode(a);
        data.putNode(b);
        data.putEdge(edge(a, b));

        RoadGraphRoutingService service = new RoadGraphRoutingService(new RoadGraphRepository(data));

        assertTrue(service.isRoadCorridor("minecraft:overworld", new BlockPos(5, 64, 1), 1));
    }

    private static RoadGraphNodeRecord node(BlockPos pos) {
        return new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld", pos,
                RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
    }

    private static RoadGraphEdgeRecord edge(RoadGraphNodeRecord from, RoadGraphNodeRecord to) {
        List<BlockPos> centerline = interpolate(from.pos(), to.pos());
        return new RoadGraphEdgeRecord(UUID.randomUUID(), from.nodeId(), to.nodeId(), "minecraft:overworld",
                "alpha", "", "", "", "", "", "edge", 3, CompiledRoadSectionType.ROAD,
                RoadGraphEdgeRecord.Status.BUILT, centerline, List.of(from.pos(), to.pos()),
                centerline.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south()))).toList(),
                List.of(), List.of(), 1L, 1L);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> points = new java.util.ArrayList<>();
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
