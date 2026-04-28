package com.monpai.sailboatmod.client.roadplanner;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapViewTest {
    @Test
    void mapConsumesMostOfTheScreenLikeWorldMap() {
        RoadPlannerMapLayout layout = RoadPlannerMapLayout.compute(1280, 720);

        assertTrue(layout.map().width() >= 1000);
        assertTrue(layout.map().height() >= 600);
        assertEquals(1280, layout.toolbar().width());
        assertTrue(layout.toolbar().height() <= 40);
        assertTrue(layout.statusBar().height() <= 42);
    }

    @Test
    void viewSupportsPanAndZoomAroundCursor() {
        RoadPlannerMapLayout.Rect map = new RoadPlannerMapLayout.Rect(40, 30, 1000, 600);
        RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, 1.0D);

        int beforeX = view.screenToWorldX(540, map);
        int beforeZ = view.screenToWorldZ(330, map);
        view.zoomAround(540, 330, 1.5D, map);

        assertEquals(beforeX, view.screenToWorldX(540, map));
        assertEquals(beforeZ, view.screenToWorldZ(330, map));

        view.panByScreenDelta(150, -90);

        assertTrue(view.centerX() < 0);
        assertTrue(view.centerZ() > 0);
    }

    @Test
    void mapCanvasUsesHeightSamplerForMouseWorldPosition() {
        RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, 2.0D);
        RoadPlannerMapLayout.Rect map = new RoadPlannerMapLayout.Rect(100, 100, 400, 300);
        RoadPlannerMapCanvas canvas = RoadPlannerMapCanvas.forTest(map.asVanillaRect(), view, (x, z) -> 72);

        BlockPos pos = canvas.mouseToWorld(300, 250);

        assertEquals(72, pos.getY());
    }
}
