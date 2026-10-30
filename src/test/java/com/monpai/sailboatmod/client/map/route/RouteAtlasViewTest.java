package com.monpai.sailboatmod.client.map.route;

import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapLayout;
import com.monpai.sailboatmod.client.roadplanner.RoadPlannerMapView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class RouteAtlasViewTest {
    private static final RoadPlannerMapLayout.Rect MAP = new RoadPlannerMapLayout.Rect(20, 30, 320, 200);

    @Test
    void sameContextRetainsThePannedAndZoomedViewDespitePlayerMovementAndDifferentMapBounds() {
        RouteAtlasView atlas = new RouteAtlasView();
        assertEquals(0, atlas.view().centerX());
        assertEquals(0, atlas.view().centerZ());
        assertEquals(0.5, atlas.view().scale());

        atlas.updateContext("world_a", "minecraft:overworld", 400, -200);
        assertEquals(400, atlas.view().centerX());
        assertEquals(-200, atlas.view().centerZ());
        RoadPlannerMapView view = atlas.view();
        view.panByScreenDelta(40, -20);
        view.zoomAround(210, 150, 2, MAP);
        double centerX = view.centerX();
        double centerZ = view.centerZ();
        double scale = view.scale();

        atlas.updateContext("world_a", "minecraft:overworld", 10_000, 5_000);
        atlas.updateContext("world_a", "minecraft:overworld", -500, -800);

        assertSame(view, atlas.view(), "refreshing or reopening the same world must retain the existing map view");
        assertEquals(centerX, atlas.view().centerX());
        assertEquals(centerZ, atlas.view().centerZ());
        assertEquals(scale, atlas.view().scale());
        RoadPlannerMapLayout.Rect resized = new RoadPlannerMapLayout.Rect(90, 70, 640, 420);
        assertEquals(resized.x() + resized.width() / 2, atlas.view().worldToScreenX(centerX, resized));
        assertEquals(resized.y() + resized.height() / 2, atlas.view().worldToScreenZ(centerZ, resized));
    }

    @Test
    void changingEitherDimensionOrWorldRecentersOnThePlayerAndRestoresTheInitialScale() {
        RouteAtlasView atlas = new RouteAtlasView();
        atlas.updateContext("world_a", "minecraft:overworld", 0, 0);
        atlas.view().panByScreenDelta(100, 50);
        atlas.view().zoomAround(180, 130, 4, MAP);

        atlas.updateContext("world_a", "minecraft:the_nether", -450, -800);
        assertEquals(-450, atlas.view().centerX());
        assertEquals(-800, atlas.view().centerZ());
        assertEquals(0.5, atlas.view().scale());

        atlas.view().panByScreenDelta(-100, 50);
        atlas.view().zoomAround(180, 130, 0.25, MAP);
        atlas.updateContext("world_b", "minecraft:the_nether", 75_000_000, -67_000_000);
        assertEquals(75_000_000, atlas.view().centerX());
        assertEquals(-67_000_000, atlas.view().centerZ());
        assertEquals(0.5, atlas.view().scale());
    }

    @Test
    void explicitRecenterUsesTheRequestedCoordinatesWithoutChangingTheChosenZoom() {
        RouteAtlasView atlas = new RouteAtlasView();
        atlas.updateContext("world_a", "minecraft:overworld", 40, 80);
        atlas.view().zoomAround(210, 150, 4, MAP);
        atlas.view().panByScreenDelta(70, -30);
        double scale = atlas.view().scale();

        atlas.recenter(-1_000.25, 2_000.75);

        assertEquals(-1_000.25, atlas.view().centerX());
        assertEquals(2_000.75, atlas.view().centerZ());
        assertEquals(scale, atlas.view().scale());
        assertEquals(MAP.x() + MAP.width() / 2, atlas.view().worldToScreenX(-1_000.25, MAP));
        assertEquals(MAP.y() + MAP.height() / 2, atlas.view().worldToScreenZ(2_000.75, MAP));
    }
}
