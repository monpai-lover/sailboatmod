package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.network.packet.roadplanner.RoadPlannerRoadOverlaySyncPacket;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeRelationship;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeScope;
import com.monpai.sailboatmod.roadplanner.model.RoadPlannerMergeSelection;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerRoadOverlayHitTesterTest {
    @Test
    void findsRoadSegmentWithinPixelThreshold() {
        RoadPlannerRoadOverlayHitTester.Result hit = RoadPlannerRoadOverlayHitTester.find(
                50,
                3,
                List.of(overlay("road-a", RoadPlannerMergeRelationship.TRADE, point(0, 0), point(100, 0))),
                BlockPos::getX,
                BlockPos::getZ,
                RoadPlannerMergeSelection.none(),
                5.0D);

        assertTrue(hit.hit());
        assertEquals("road-a", hit.entry().roadId());
        assertEquals(0, hit.segmentIndex());
    }

    @Test
    void ignoresRoadSegmentsOutsideThreshold() {
        RoadPlannerRoadOverlayHitTester.Result hit = RoadPlannerRoadOverlayHitTester.find(
                50,
                9,
                List.of(overlay("road-a", RoadPlannerMergeRelationship.TRADE, point(0, 0), point(100, 0))),
                BlockPos::getX,
                BlockPos::getZ,
                RoadPlannerMergeSelection.none(),
                5.0D);

        assertFalse(hit.hit());
    }

    @Test
    void selectedMergeRoadWinsEqualDistanceTie() {
        RoadPlannerMergeSelection selected = new RoadPlannerMergeSelection(
                "trade-road",
                0,
                point(0, 0),
                RoadPlannerMergeScope.ALLIED_OR_TRADE);

        RoadPlannerRoadOverlayHitTester.Result hit = RoadPlannerRoadOverlayHitTester.find(
                50,
                0,
                List.of(
                        overlay("own-road", RoadPlannerMergeRelationship.OWN, point(0, 0), point(100, 0)),
                        overlay("trade-road", RoadPlannerMergeRelationship.TRADE, point(0, 0), point(100, 0))),
                BlockPos::getX,
                BlockPos::getZ,
                selected,
                5.0D);

        assertTrue(hit.hit());
        assertEquals("trade-road", hit.entry().roadId());
    }

    @Test
    void ownRoadWinsEqualDistanceWhenNoSelectedMergeRoad() {
        RoadPlannerRoadOverlayHitTester.Result hit = RoadPlannerRoadOverlayHitTester.find(
                50,
                0,
                List.of(
                        overlay("trade-road", RoadPlannerMergeRelationship.TRADE, point(0, 0), point(100, 0)),
                        overlay("own-road", RoadPlannerMergeRelationship.OWN, point(0, 0), point(100, 0))),
                BlockPos::getX,
                BlockPos::getZ,
                RoadPlannerMergeSelection.none(),
                5.0D);

        assertTrue(hit.hit());
        assertEquals("own-road", hit.entry().roadId());
    }

    @Test
    void nodeHitTestingUsesSparseDisplayPathInsteadOfDenseCanonicalPath() {
        RoadPlannerRoadOverlaySyncPacket.Entry overlay = new RoadPlannerRoadOverlaySyncPacket.Entry(
                "road-a",
                RoadPlannerMergeRelationship.OWN,
                List.of(point(0, 0), point(50, 0), point(100, 0)),
                List.of(point(0, 0), point(100, 0)),
                List.of(0, 2),
                List.of(),
                "Alpha - Beta",
                100,
                "Builder",
                "uuid-a",
                1L,
                false);

        RoadPlannerRoadOverlayHitTester.NodeResult hit = RoadPlannerRoadOverlayHitTester.findNode(
                50,
                0,
                List.of(overlay),
                BlockPos::getX,
                BlockPos::getZ,
                RoadPlannerMergeSelection.none(),
                5.0D);

        assertFalse(hit.hit());
    }

    private static RoadPlannerRoadOverlaySyncPacket.Entry overlay(String roadId,
                                                                 RoadPlannerMergeRelationship relationship,
                                                                 BlockPos... path) {
        return new RoadPlannerRoadOverlaySyncPacket.Entry(roadId, relationship, List.of(path));
    }

    private static BlockPos point(int x, int z) {
        return new BlockPos(x, 64, z);
    }
}
