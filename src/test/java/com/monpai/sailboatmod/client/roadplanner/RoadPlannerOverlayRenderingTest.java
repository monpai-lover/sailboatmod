package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerOverlayRenderingTest {
    @Test
    void lodSimplificationKeepsEndpointsAndReducesDensePolyline() {
        List<BlockPos> path = List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(12, 64, 0),
                new BlockPos(16, 64, 0));

        List<BlockPos> simplified = RoadPlannerOverlayLod.simplify(path, 8);

        assertEquals(new BlockPos(0, 64, 0), simplified.get(0));
        assertEquals(new BlockPos(16, 64, 0), simplified.get(simplified.size() - 1));
        assertTrue(simplified.size() < path.size());
    }

    @Test
    void renderModelPromotesSelectedReuseRoadWithoutMarkingUnrelatedRoads() {
        List<RoadPlannerRoadOverlaySyncPacket.Entry> overlays = List.of(
                road("road-a", new BlockPos(0, 64, 0), new BlockPos(40, 64, 0)),
                road("road-b", new BlockPos(0, 64, 40), new BlockPos(40, 64, 40)));

        List<RoadPlannerRoadOverlayRenderModel.RoadLayer> layers = RoadPlannerRoadOverlayRenderModel.layers(
                overlays,
                new RoadPlannerMergeSelection("road-a", 0, new BlockPos(0, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                Set.of("road-a"),
                "",
                1);

        assertTrue(layers.stream().anyMatch(layer ->
                layer.roadId().equals("road-a") && layer.kind() == RoadPlannerRoadOverlayRenderModel.Kind.SELECTED_REUSE));
        assertTrue(layers.stream().anyMatch(layer ->
                layer.roadId().equals("road-b") && layer.kind() == RoadPlannerRoadOverlayRenderModel.Kind.BASE));
    }

    @Test
    void keyNodesShowsDisplayPathNodesAtInteractiveLod() {
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = road(
                "road-a",
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                new BlockPos(80, 64, 0));

        List<BlockPos> nodes = RoadPlannerRoadOverlayRenderModel.keyNodes(
                overlay,
                new RoadPlannerMergeSelection("road-a", 1, new BlockPos(40, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                Set.of("road-a"),
                null,
                true,
                1);

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(40, 64, 0),
                new BlockPos(80, 64, 0)), nodes);
    }

    @Test
    void keyNodesUseLodToAvoidDenseNodeFloodButKeepSelectedAnchor() {
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = road(
                "road-a",
                new BlockPos(0, 64, 0),
                new BlockPos(2, 64, 0),
                new BlockPos(4, 64, 0),
                new BlockPos(6, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(10, 64, 0));

        List<BlockPos> nodes = RoadPlannerRoadOverlayRenderModel.keyNodes(
                overlay,
                new RoadPlannerMergeSelection("road-a", 2, new BlockPos(4, 64, 0), RoadPlannerMergeScope.OWN_NATION),
                Set.of("road-a"),
                null,
                true,
                8);

        assertEquals(List.of(
                new BlockPos(0, 64, 0),
                new BlockPos(8, 64, 0),
                new BlockPos(10, 64, 0),
                new BlockPos(4, 64, 0)), nodes);
    }

    @Test
    void solidAxisAlignedThickLineUsesSingleFillOperation() {
        int fills = RoadPlannerOverlayLineRenderer.estimatedFillCallsForTest(
                0, 10, 600, 10, 6, 0, 0);

        assertEquals(1, fills);
    }

    private RoadPlannerRoadOverlaySyncPacket.Entry road(String roadId, BlockPos... path) {
        return new RoadPlannerRoadOverlaySyncPacket.Entry(roadId, RoadPlannerMergeRelationship.OWN, List.of(path));
    }
}
