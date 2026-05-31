package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphSpatialIndexTest {
    @Test
    void findsNearbyBuiltEdgeByPlacementFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord edge = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        index.rebuild(List.of(edge));

        List<RoadGraphSpatialIndex.EdgeHit> hits = index.near(new BlockPos(8, 64, 1), 3);

        assertEquals(edge.edgeId(), hits.get(0).edge().edgeId());
        assertTrue(hits.get(0).distanceSqr() <= 9);
    }

    @Test
    void queriesViewportByCenterlineAndFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord inside = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        RoadGraphEdgeRecord outside = edge(new BlockPos(500, 64, 500), new BlockPos(516, 64, 500));
        index.rebuild(List.of(inside, outside));

        List<RoadGraphEdgeRecord> visible = index.queryRect("minecraft:overworld", -32, -32, 32, 32);

        assertEquals(List.of(inside.edgeId()), visible.stream().map(RoadGraphEdgeRecord::edgeId).toList());
    }

    @Test
    void classifiesRoadCorridorMembershipFromFootprint() {
        RoadGraphSpatialIndex index = new RoadGraphSpatialIndex();
        RoadGraphEdgeRecord edge = edge(new BlockPos(0, 64, 0), new BlockPos(16, 64, 0));
        index.rebuild(List.of(edge));

        assertTrue(index.isRoadCorridor("minecraft:overworld", new BlockPos(8, 64, 1), 1));
    }

    private static RoadGraphEdgeRecord edge(BlockPos from, BlockPos to) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID edgeId = UUID.randomUUID();
        List<BlockPos> centerline = interpolate(from, to);
        List<RoadGraphSegmentPlacement> placements = centerline.stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
        return new RoadGraphEdgeRecord(edgeId, a, b, "minecraft:overworld", "alpha", "", "", "",
                "", "", "test", 3, CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                centerline, List.of(from, to), placements, placements.stream().flatMap(p -> p.positions().stream()).toList(),
                List.of(), 1L, 1L);
    }

    private static List<BlockPos> interpolate(BlockPos from, BlockPos to) {
        java.util.ArrayList<BlockPos> points = new java.util.ArrayList<>();
        int dx = Integer.compare(to.getX() - from.getX(), 0);
        int dz = Integer.compare(to.getZ() - from.getZ(), 0);
        BlockPos cursor = from;
        points.add(cursor);
        while (!cursor.equals(to)) {
            cursor = cursor.offset(dx, Integer.compare(to.getY() - cursor.getY(), 0), dz);
            points.add(cursor);
        }
        return List.copyOf(points);
    }
}
