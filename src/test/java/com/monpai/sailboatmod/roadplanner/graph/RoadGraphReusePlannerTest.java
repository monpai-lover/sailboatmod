package com.monpai.sailboatmod.roadplanner.graph;

import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadGraphReusePlannerTest {
    @Test
    void detectsDirectionCompatibleReusableRun() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.ROAD,
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(0, 64, 2), new BlockPos(20, 64, 2));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(0, 64, 2), new BlockPos(20, 64, 2)),
                CompiledRoadSectionType.ROAD);

        assertEquals(1, plan.reuseSpans().size());
        assertTrue(plan.reuseSpans().get(0).plannedToIndex() - plan.reuseSpans().get(0).plannedFromIndex() >= 3);
        assertTrue(plan.ownedRanges().isEmpty());
    }

    @Test
    void rejectsPerpendicularNearbyRun() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.ROAD,
                new BlockPos(0, 64, 0),
                new BlockPos(20, 64, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(10, 64, -10), new BlockPos(10, 64, 10));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(10, 64, -10), new BlockPos(10, 64, 10)),
                CompiledRoadSectionType.ROAD);

        assertTrue(plan.reuseSpans().isEmpty());
        assertEquals(List.of(new RoadReusePlan.Range(0, planned.size() - 1)), plan.ownedRanges());
    }

    @Test
    void rejectsRoadToBridgeReuseByDefault() {
        RoadGraphRepository repository = repositoryWith(edge(
                CompiledRoadSectionType.BRIDGE,
                new BlockPos(0, 70, 0),
                new BlockPos(20, 70, 0)));
        List<RoadGraphSegmentPlacement> planned = placements(new BlockPos(0, 70, 1), new BlockPos(20, 70, 1));

        RoadReusePlan plan = new RoadGraphReusePlanner(repository).planReuse(
                "minecraft:overworld",
                planned,
                List.of(new BlockPos(0, 70, 1), new BlockPos(20, 70, 1)),
                CompiledRoadSectionType.ROAD);

        assertTrue(plan.reuseSpans().isEmpty());
        assertEquals("incompatible_section_type", plan.rejections().get(0).reason());
    }

    private static RoadGraphRepository repositoryWith(RoadGraphEdgeRecord edge) {
        RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();
        data.putNode(new RoadGraphNodeRecord(edge.fromNodeId(), edge.dimensionId(), edge.centerline().get(0),
                RoadGraphNodeRecord.Kind.NORMAL, edge.ownerNationId(), edge.ownerTownId(), "", 1L, 1L));
        data.putNode(new RoadGraphNodeRecord(edge.toNodeId(), edge.dimensionId(), edge.centerline().get(edge.centerline().size() - 1),
                RoadGraphNodeRecord.Kind.NORMAL, edge.ownerNationId(), edge.ownerTownId(), "", 1L, 1L));
        data.putEdge(edge);
        return new RoadGraphRepository(data);
    }

    private static RoadGraphEdgeRecord edge(CompiledRoadSectionType type, BlockPos from, BlockPos to) {
        UUID fromId = UUID.randomUUID();
        UUID toId = UUID.randomUUID();
        List<BlockPos> centerline = interpolate(from, to);
        List<RoadGraphSegmentPlacement> placements = centerline.stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
        return new RoadGraphEdgeRecord(UUID.randomUUID(), fromId, toId, "minecraft:overworld", "alpha", "",
                "", "", "", "", "source", 3, type, RoadGraphEdgeRecord.Status.BUILT,
                centerline, List.of(from, to), placements, List.of(), List.of(), 1L, 1L);
    }

    private static List<RoadGraphSegmentPlacement> placements(BlockPos from, BlockPos to) {
        return interpolate(from, to).stream()
                .map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos, pos.north(), pos.south())))
                .toList();
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
