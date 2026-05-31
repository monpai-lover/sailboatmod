package com.monpai.sailboatmod.nation.service;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerSegmentType;
import com.monpai.sailboatmod.roadplanner.compile.CompiledRoadSectionType;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphEdgeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphNodeRecord;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphRepository;
import com.monpai.sailboatmod.roadplanner.graph.RoadGraphSegmentPlacement;
import com.monpai.sailboatmod.roadplanner.graph.RoadNetworkGraphSavedData;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerAutoMergeRouteServiceTest {
    @Test
    void findsFullExistingRoadRouteToDestination() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord a = graph.node(40, 0);
        RoadGraphNodeRecord b = graph.node(80, 0);
        RoadGraphNodeRecord c = graph.node(120, 0);
        RoadGraphEdgeRecord ab = graph.edge("ab", a, b);
        RoadGraphEdgeRecord bc = graph.edge("bc", b, c);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        12,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(ab.edgeId().toString(), result.mergeSelection().roadId());
        assertEquals(new BlockPos(40, 64, 0), result.mergeSelection().anchorPos());
        assertEquals(List.of(new BlockPos(40, 64, 0), new BlockPos(80, 64, 0), new BlockPos(120, 64, 0)),
                result.displayPath());
        assertEquals(2, result.sharedSpans().size());
        assertEquals(ab.edgeId().toString(), result.sharedSpans().get(0).roadId());
        assertEquals(bc.edgeId().toString(), result.sharedSpans().get(1).roadId());
    }

    @Test
    void rejectsRoadThatCannotReachDestination() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord a = graph.node(40, 0);
        RoadGraphNodeRecord b = graph.node(80, 0);
        RoadGraphNodeRecord far = graph.node(200, 0);
        graph.edge("ab", a, b);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        far.pos(),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        12,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.NOT_FOUND, result.status());
        assertFalse(result.mergeSelection().present());
        assertTrue(result.displayPath().isEmpty());
    }

    @Test
    void directionPriorityBeatsCloserButWrongTurnRoute() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord straightA = graph.node(40, 0);
        RoadGraphNodeRecord straightB = graph.node(120, 0);
        RoadGraphNodeRecord northA = graph.node(36, -12);
        RoadGraphNodeRecord northB = graph.node(120, 0);
        RoadGraphEdgeRecord straight = graph.edge("straight", straightA, straightB);
        graph.edge("north", northA, northB);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        24,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(straight.edgeId().toString(), result.mergeSelection().roadId());
    }

    @Test
    void manualPreferredRoadOverridesAutomaticEntryWhenReachable() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord lowerA = graph.node(40, 0);
        RoadGraphNodeRecord lowerB = graph.node(120, 0);
        RoadGraphNodeRecord upperA = graph.node(40, 16);
        RoadGraphNodeRecord upperB = graph.node(120, 0);
        graph.edge("lower", lowerA, lowerB);
        RoadGraphEdgeRecord upper = graph.edge("upper", upperA, upperB);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.ROAD),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        new RoadPlannerAutoMergeRouteService.PreferredEntry(upper.edgeId().toString(), -1),
                        24,
                        20));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.FOUND, result.status());
        assertEquals(upper.edgeId().toString(), result.mergeSelection().roadId());
    }

    @Test
    void bridgeLikeFinalSegmentDisablesAutoMerge() {
        GraphFixture graph = new GraphFixture();
        RoadGraphNodeRecord a = graph.node(40, 0);
        RoadGraphNodeRecord b = graph.node(120, 0);
        graph.edge("ab", a, b);

        RoadPlannerAutoMergeRouteService.Result result = RoadPlannerAutoMergeRouteService.resolve(
                new RoadPlannerAutoMergeRouteService.Query(
                        graph.repository(),
                        "minecraft:overworld",
                        List.of(new BlockPos(0, 64, 0), new BlockPos(36, 64, 0)),
                        List.of(RoadPlannerSegmentType.BRIDGE_MAJOR),
                        new BlockPos(120, 64, 0),
                        RoadPlannerMergeScope.OWN_NATION,
                        RoadPlannerAutoMergeRouteService.PreferredEntry.none(),
                        24,
                        12));

        assertEquals(RoadPlannerAutoMergeRouteService.Status.NOT_FOUND, result.status());
        assertFalse(result.mergeSelection().present());
    }

    private static final class GraphFixture {
        private final RoadNetworkGraphSavedData data = new RoadNetworkGraphSavedData();

        RoadGraphRepository repository() {
            return new RoadGraphRepository(data);
        }

        RoadGraphNodeRecord node(int x, int z) {
            RoadGraphNodeRecord node = new RoadGraphNodeRecord(UUID.randomUUID(), "minecraft:overworld",
                    new BlockPos(x, 64, z), RoadGraphNodeRecord.Kind.NORMAL, "alpha", "", "", 1L, 1L);
            data.putNode(node);
            return node;
        }

        RoadGraphEdgeRecord edge(String name, RoadGraphNodeRecord from, RoadGraphNodeRecord to) {
            List<BlockPos> centerline = List.of(from.pos(), to.pos());
            RoadGraphEdgeRecord edge = new RoadGraphEdgeRecord(UUID.randomUUID(), from.nodeId(), to.nodeId(),
                    "minecraft:overworld", "alpha", "", "", "", "Alpha", "Beta", name, 3,
                    CompiledRoadSectionType.ROAD, RoadGraphEdgeRecord.Status.BUILT,
                    centerline, centerline,
                    centerline.stream().map(pos -> new RoadGraphSegmentPlacement(pos, List.of(pos))).toList(),
                    List.of(), List.of(), 1L, 1L);
            data.putEdge(edge);
            return edge;
        }
    }
}
