package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import com.monpai.sailboatmod.map.route.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SharedMapRouteProjectionTest {
    private static final RoadPlannerMapLayout.Rect RECT = new RoadPlannerMapLayout.Rect(20, 30, 200, 100);

    @Test
    void hitToleranceStaysInScreenPixelsAcrossZoom() {
        MapRouteEntry route = route(List.of(new MapRoutePoint(-10_000, 0), new MapRoutePoint(10_000, 0)));
        for (double scale : List.of(0.01, 1.0, 8.0)) {
            var view = RoadPlannerMapView.centered(0, 0, scale);
            assertEquals("route", SharedMapRouteProjection.hit(List.of(route), view, RECT, 120, 85, 6).orElseThrow().id());
            assertTrue(SharedMapRouteProjection.hit(List.of(route), view, RECT, 120, 87, 6).isEmpty());
        }
    }

    @Test
    void projectedLinesUseTheTerrainTransformAtLargeNegativeCoordinates() {
        var view = RoadPlannerMapView.centered(-75_000_000, 67_000_000, 0.5);
        var a = new MapRoutePoint(-75_000_020, 67_000_010);
        var b = new MapRoutePoint(-74_999_980, 67_000_010);
        var segments = SharedMapRouteProjection.segments(route(List.of(a, b)), view, RECT);
        assertEquals(1, segments.size());
        assertEquals(view.worldToScreenX(a.x(), RECT), segments.getFirst().x1());
        assertEquals(view.worldToScreenZ(a.z(), RECT), segments.getFirst().y1());
        assertEquals(view.worldToScreenX(b.x(), RECT), segments.getFirst().x2());
        assertEquals(view.worldToScreenZ(b.z(), RECT), segments.getFirst().y2());
    }

    @Test
    void crossingSegmentsWithBothEndpointsOutsideAreVisibleAndClickable() {
        var view = RoadPlannerMapView.centered(0, 0, 1);
        var route = route(List.of(new MapRoutePoint(-500, 0), new MapRoutePoint(500, 0)));
        var segments = SharedMapRouteProjection.segments(route, view, RECT);
        assertEquals(1, segments.size());
        assertEquals(RECT.x(), segments.getFirst().x1());
        assertEquals(RECT.right(), segments.getFirst().x2());
        assertTrue(SharedMapRouteProjection.hit(List.of(route), view, RECT, 120, 80, 6).isPresent());
        assertTrue(SharedMapRouteProjection.hit(List.of(route), view, RECT, 10, 80, 6).isEmpty());
    }

    @Test
    void disconnectedPartsDoNotCreateFalseHitThroughTheGap() {
        var view = RoadPlannerMapView.centered(0, 0, 1);
        var route = new MapRouteEntry("route", MapRouteKind.SEA, "route", "A", "B", 20,
                List.of(List.of(new MapRoutePoint(-90, 0), new MapRoutePoint(-50, 0)),
                        List.of(new MapRoutePoint(50, 0), new MapRoutePoint(90, 0))));
        assertEquals(2, SharedMapRouteProjection.segments(route, view, RECT).size());
        assertTrue(SharedMapRouteProjection.hit(List.of(route), view, RECT, 120, 80, 6).isEmpty());
    }

    private static MapRouteEntry route(List<MapRoutePoint> path) {
        return new MapRouteEntry("route", MapRouteKind.ROAD, "route", "A", "B", 100, List.of(path));
    }
}
