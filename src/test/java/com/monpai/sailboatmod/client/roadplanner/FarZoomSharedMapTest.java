package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.SharedMapTileGrid;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FarZoomSharedMapTest {
    @Test void sharedViewCanShowFortyMillionBlocksAndKeepsTheCursorAnchored() {
        RoadPlannerMapLayout.Rect rect = new RoadPlannerMapLayout.Rect(0, 0, 1280, 720);
        RoadPlannerMapView view = RoadPlannerMapView.centered(33_431_923, -1_200_000, 1.0 / 65_536);
        assertEquals(1.0 / 65_536, view.scale());
        assertTrue((long) view.screenToWorldX(rect.right(), rect) - view.screenToWorldX(rect.x(), rect) >= 40_000_000);
        int beforeX = view.screenToWorldX(800, rect), beforeZ = view.screenToWorldZ(350, rect);
        view.zoomAround(800, 350, 0.5, rect);
        assertEquals(beforeX, view.screenToWorldX(800, rect));
        assertEquals(beforeZ, view.screenToWorldZ(350, rect));
    }
    @Test void visibleTilePlanningRemainsBoundedAtAllFarScalesAndScreenSizes() {
        for (int width : new int[]{320, 1280, 3840}) for (double scale : new double[]{1.0/256, 1.0/65_536, 1.0/1_048_576}) {
            RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(0, 0, width, width * 9 / 16);
            RoadPlannerMapView view = RoadPlannerMapView.centered(33_431_923, -10_000_000, scale);
            List<RoadPlannerMapTileRenderPlanner.TileRequest> requests = RoadPlannerMapTileRenderPlanner.plan(rect, view);
            assertFalse(requests.isEmpty());
            assertTrue(requests.size() <= 128, "far views have a fixed tile budget, width=" + width + " scale=" + scale);
            assertTrue(SharedMapTileGrid.forScale(view.scale(), rect.width(), rect.height()) > 1);
        }
    }
}
