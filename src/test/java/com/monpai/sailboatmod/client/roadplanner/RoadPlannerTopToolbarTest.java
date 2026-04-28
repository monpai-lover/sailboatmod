package com.monpai.sailboatmod.client.roadplanner;

import com.monpai.sailboatmod.roadplanner.model.RoadToolType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoadPlannerTopToolbarTest {
    @Test
    void collapsedToolbarShowsOnlyGroups() {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.defaultToolbar(1280);

        assertEquals(RoadPlannerTopToolbar.GROUP_TOOLS, toolbar.items().get(0).label());
        assertEquals(RoadPlannerTopToolbar.Kind.GROUP, toolbar.items().get(0).kind());
        assertEquals(RoadPlannerTopToolbar.Group.TOOLS, toolbar.items().get(0).group());
        assertEquals(RoadPlannerTopToolbar.GROUP_EDIT, toolbar.items().get(1).label());
        assertEquals(RoadPlannerTopToolbar.GROUP_ROUTE, toolbar.items().get(2).label());
        assertTrue(toolbar.bounds().height() <= 34);
    }

    @Test
    void expandedToolsIncludeWaterCrossingBezierEndpointAndForceRender() {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS);

        assertTrue(toolbar.items().stream().anyMatch(item -> item.toolType() == RoadToolType.WATER_CROSSING));
        assertTrue(toolbar.items().stream().anyMatch(item -> item.toolType() == RoadToolType.BEZIER));
        assertTrue(toolbar.items().stream().anyMatch(item -> item.toolType() == RoadToolType.ENDPOINT));
        assertTrue(toolbar.items().stream().anyMatch(item -> item.toolType() == RoadToolType.FORCE_RENDER));
        assertTrue(toolbar.items().stream().noneMatch(item -> "\u8d77\u70b9".equals(item.label()) || "\u7ec8\u70b9".equals(item.label())));
    }

    @Test
    void hitTestingFindsExpandedToolbarItem() {
        RoadPlannerTopToolbar toolbar = RoadPlannerTopToolbar.toolbar(1280, RoadPlannerTopToolbar.Group.TOOLS);
        RoadPlannerTopToolbar.Item road = toolbar.items().stream()
                .filter(item -> item.toolType() == RoadToolType.ROAD)
                .findFirst()
                .orElseThrow();

        RoadPlannerTopToolbar.Item hit = toolbar.itemAt(road.bounds().x() + 2, road.bounds().y() + 2);

        assertNotNull(hit);
        assertEquals(RoadToolType.ROAD, hit.toolType());
    }
}
