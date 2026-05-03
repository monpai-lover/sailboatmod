package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.map.MapLod;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerMapTileRenderPlannerTest {
    @Test
    void usesLod1ForEveryZoomScale() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(0, 0, 320, 240);

        for (double scale : List.of(3.0D, 1.0D, 0.45D, 0.25D)) {
            RoadPlannerMapView view = RoadPlannerMapView.centered(0, 0, scale);

            List<RoadPlannerMapTileRenderPlanner.TileRequest> requests = RoadPlannerMapTileRenderPlanner.plan(rect, view);

            assertFalse(requests.isEmpty(), "scale " + scale + " should produce visible tile requests");
            assertTrue(requests.stream().allMatch(request -> request.lod() == MapLod.LOD_1),
                    "scale " + scale + " must not select a lower-detail LOD: " + requests);
        }
    }

    @Test
    void zoomChangesDrawSizeWithoutChangingLod() {
        RoadPlannerVanillaLayout.Rect rect = new RoadPlannerVanillaLayout.Rect(0, 0, 320, 240);
        RoadPlannerMapView closeView = RoadPlannerMapView.centered(0, 0, 2.0D);
        RoadPlannerMapView farView = RoadPlannerMapView.centered(0, 0, 0.5D);

        RoadPlannerMapTileRenderPlanner.TileRequest closeRequest = RoadPlannerMapTileRenderPlanner.plan(rect, closeView).get(0);
        RoadPlannerMapTileRenderPlanner.TileRequest farRequest = RoadPlannerMapTileRenderPlanner.plan(rect, farView).get(0);

        assertEquals(MapLod.LOD_1, closeRequest.lod());
        assertEquals(MapLod.LOD_1, farRequest.lod());
        assertTrue(closeRequest.screenSize() > farRequest.screenSize(),
                "zoom should change GUI draw size instead of selecting another LOD");
    }
}
